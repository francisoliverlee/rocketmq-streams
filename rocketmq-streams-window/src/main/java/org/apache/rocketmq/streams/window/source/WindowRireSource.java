/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.streams.window.source;

import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.rocketmq.streams.common.channel.sinkcache.IMessageCache;
import org.apache.rocketmq.streams.common.channel.sinkcache.IMessageFlushCallBack;
import org.apache.rocketmq.streams.common.channel.sinkcache.impl.AbstractMutilSplitMessageCache;
import org.apache.rocketmq.streams.common.channel.source.AbstractSource;
import org.apache.rocketmq.streams.common.channel.source.AbstractSupportOffsetResetSource;
import org.apache.rocketmq.streams.common.context.AbstractContext;
import org.apache.rocketmq.streams.common.context.IMessage;
import org.apache.rocketmq.streams.common.context.Message;
import org.apache.rocketmq.streams.common.interfaces.IStreamOperator;
import org.apache.rocketmq.streams.common.utils.DateUtil;
import org.apache.rocketmq.streams.window.debug.DebugWriter;
import org.apache.rocketmq.streams.window.model.WindowInstance;
import org.apache.rocketmq.streams.window.operator.AbstractWindow;
import org.apache.rocketmq.streams.window.shuffle.ShuffleChannel;

public class WindowRireSource extends AbstractSupportOffsetResetSource implements IStreamOperator {
    protected static final Log LOG = LogFactory.getLog(WindowRireSource.class);
    private AbstractWindow window;
    protected transient Long eventTimeLastUpdateTime;
    protected transient ScheduledExecutorService fireCheckScheduler;//检查是否触发
    protected transient ScheduledExecutorService checkpointScheduler;
    protected transient ConcurrentHashMap<String,WindowInstance> windowInstances=new ConcurrentHashMap();
    protected transient IMessageCache<WindowInstance> fireInstanceCache=new WindowInstanceCache();
    //正在触发中的windowintance
    protected transient ConcurrentHashMap<String,WindowInstance> firingWindowInstances=new ConcurrentHashMap<>();


    //<windowinstanceId,<queueId，offset>>
    protected transient ConcurrentHashMap<String,Map<String,String>> windowInstanceQueueOffsets=new ConcurrentHashMap<>();

    public WindowRireSource(AbstractWindow window){
        this.window=window;
    }

    @Override
    protected boolean initConfigurable() {
        fireCheckScheduler=new ScheduledThreadPoolExecutor(2);
        checkpointScheduler=new ScheduledThreadPoolExecutor(3);
        setReceiver(window.getFireReceiver());
        fireInstanceCache.openAutoFlush();
        return super.initConfigurable();
    }
    @Override
    protected boolean startSource() {
        //检查window instance，如果已经到了触发时间，且符合触发条件，直接触发，如果到了触发时间，还未符合触发条件。则放入触发列表。下次调度时间是下一个最近触发的时间

        fireCheckScheduler.scheduleWithFixedDelay(new Runnable() {
            //  long startTime=System.currentTimeMillis();
            @Override
            public void run() {
                try {
                    //System.out.println("fire schdule time is "+(System.currentTimeMillis()-startTime)+" windowinstance count is "+windowInstances.size());
                    //startTime=System.currentTimeMillis();
                    if(windowInstances.size()==0){
                        //if(eventTimeLastUpdateTime!=null){
                        //    int gap=(int)(System.currentTimeMillis()-eventTimeLastUpdateTime);
                        //    if(window.getMsgMaxGapSecond()!=null&&gap>window.getMsgMaxGapSecond()*1000){
                        //        for(String key:fireCounts.keySet()){
                        //            Integer count=fireCounts.get(key);
                        //            if(count==0){
                        //                System.out.println("===================== "+key+":"+count);
                        //            }
                        //        }
                        //    }
                        //}
                        return;

                    }
                    List<WindowInstance> windowInstanceList=new ArrayList<>();
                    windowInstanceList.addAll(windowInstances.values());
                    long fireStartTime=System.currentTimeMillis();
                    Collections.sort(windowInstanceList, new Comparator<WindowInstance>() {
                        @Override
                        public int compare(WindowInstance o1, WindowInstance o2) {
                            int value= o1.getFireTime().compareTo(o2.getFireTime());
                            if(value!=0){
                                return value;
                            }
                            return o2.getStartTime().compareTo(o1.getStartTime());
                        }
                    });
                    WindowInstance windowInstance = windowInstanceList.get(0);
                    while (windowInstance!=null){
                        boolean success= executeFireTask(windowInstance,false);
                        if(success){
                            windowInstances.remove(windowInstance.createWindowInstanceId());
                        }
                        if(windowInstances.size()==0){
                            break;
                        }
                        windowInstanceList=new ArrayList<>();
                        windowInstanceList.addAll(windowInstances.values());
                        Collections.sort(windowInstanceList, new Comparator<WindowInstance>() {
                            @Override
                            public int compare(WindowInstance o1, WindowInstance o2) {
                                int value= o1.getFireTime().compareTo(o2.getFireTime());
                                if(value!=0){
                                    return value;
                                }
                                return o2.getStartTime().compareTo(o1.getStartTime());
                            }
                        });
                        if(windowInstanceList.size()==0){
                            break;
                        }
                        windowInstance=windowInstanceList.get(0);
                    }

                } catch (Exception e) {
                    e.printStackTrace();
                }
            }

        },0,1, TimeUnit.SECONDS);

        //定时发送checkpoint，提交和保存数据。在pull模式会有用
        //fireCheckScheduler.scheduleWithFixedDelay(new Runnable() {
        //
        //    @Override
        //    public void run() {
        //       if(checkPointManager.getCurrentSplits()==null||checkPointManager.getCurrentSplits().size()==0){
        //           return;
        //       }
        //       for(WindowInstance windowInstance:firingWindowInstances.values()){
        //           Set<String> splits=checkPointManager.getCurrentSplits();
        //           Set<String> windowInstanceSplits=new HashSet<>();
        //           for(String splitId:splits){
        //               //String windowInstanceSpiltId=windowInstance.createWindowInstancePartitionId();
        //               windowInstanceSplits.add(splitId);
        //           }
        //           sendCheckpoint(windowInstanceSplits);
        //       }
        //
        //    }
        //},0,getCheckpointTime(), TimeUnit.MILLISECONDS);

        return false;
    }



    /**
     * 如果没有window instance，则注册，否则放弃
     * @param windowInstance
     */
    public void registFireWindowInstanceIfNotExist(WindowInstance windowInstance, AbstractWindow window){
        String windowInstanceId=windowInstance.createWindowInstanceId();
        WindowInstance old= windowInstances.putIfAbsent(windowInstanceId,windowInstance);
        if(old==null){
            window.getWindowInstanceMap().put(windowInstanceId,windowInstance);
        }
        LOG.debug("register window instance into manager, instance key: " + windowInstance.createWindowInstanceId());
    }
    /**
     * 注册一个window instance
     * @param windowInstance
     */
    public void updateWindowInstanceLastUpdateTime(WindowInstance windowInstance){
        String windowInstanceId=windowInstance.createWindowInstanceId();
        this.eventTimeLastUpdateTime=System.currentTimeMillis();


        windowInstances.putIfAbsent(windowInstanceId,windowInstance);

    }
    /**
     * 触发窗口
     * @param windowInstance
     */
    public boolean executeFireTask(WindowInstance windowInstance,boolean startNow) {
        String windowInstanceId=windowInstance.createWindowInstanceId();
//        JSONObject fireMsg=new JSONObject();
//        Long eventTimeLastUpdateTime=this.eventTimeLastUpdateTime;
//        fireMsg.put("start_time",windowInstance.getStartTime());
//        fireMsg.put("end_time",windowInstance.getEndTime());
//        fireMsg.put("fire_time",windowInstance.getFireTime());
//        fireMsg.put("queueid",windowInstance.getSplitId());
//        fireMsg.put("lastUpdateTime", DateUtil.format(new Date(eventTimeLastUpdateTime)));
//        fireMsg.put("sign","abc*********************************************abc");
//        List<IMessage> messages=new ArrayList<>();
//        messages.add(new Message(fireMsg));
//        ShuffleChannel.write2File(window,messages,windowInstance,windowInstance.getSplitId());
        if (canFire(windowInstance)) {
            //maybe firimg
            if (firingWindowInstances.containsKey(windowInstanceId)) {
                //System.out.println("has firing");

                return true;
            }
            //maybe fired
            //if (!window.getWindowInstanceMap().containsKey(windowInstanceId)) {
            //    return true;
            //}
            //start firing
            DebugWriter.getDebugWriter(window.getConfigureName()).writeFireWindowInstance(windowInstance,eventTimeLastUpdateTime);
            firingWindowInstances.put(windowInstanceId, windowInstance);
            if(startNow){
                fireWindowInstance(windowInstance);
            }else {
                fireInstanceCache.addCache(windowInstance);
            }
            return true;
        }
        return false;
    }
    /**
     * 触发窗口
     * @param windowInstance
     */
    protected void fireWindowInstance(WindowInstance windowInstance) {
        try {
            if (windowInstance == null) {
                LOG.error("window instance is null!");
                return;
            }
            String windowInstanceId = windowInstance.createWindowInstanceId();
            if (window == null) {
                LOG.error(windowInstanceId + "'s window object have been removed!");
                return;
            }


            if(windowInstance.getLastMaxUpdateTime()==null){
                windowInstance.setLastMaxUpdateTime(window.getMaxEventTime(windowInstance.getSplitId()));
            }
            int fireCount=window.fireWindowInstance(windowInstance,windowInstanceQueueOffsets.get(windowInstanceId));
            LOG.debug("fire instance("+windowInstance.createWindowInstanceId()+" fire count is "+fireCount);
            firingWindowInstances.remove(windowInstanceId);
            //this.maxEventTimes.remove(windowInstanceId);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 是否符合触发条件
     * @param windowInstance
     * @return
     */
    protected boolean canFire(WindowInstance windowInstance) {
        String windowInstanceId=windowInstance.createWindowInstanceId();
        if(window == null){
            LOG.warn(windowInstanceId + " can't find window!");
            return false;
        }
        Date fireTime=DateUtil.parseTime(windowInstance.getFireTime());
        /**
         * 未到触发时间
         */
        Long maxEventTime=this.window.getMaxEventTime(windowInstance.getSplitId());
        if(maxEventTime==null){
            return false;
        }
        if(maxEventTime-fireTime.getTime()>=3000){
            return true;
        }
        if(maxEventTime-fireTime.getTime()<3000){
            Long eventTimeLastUpdateTime=this.eventTimeLastUpdateTime;
            if(eventTimeLastUpdateTime==null){
                return false;
            }
            int gap=(int)(System.currentTimeMillis()-eventTimeLastUpdateTime);
            if(window.getMsgMaxGapSecond()!=null&&gap>window.getMsgMaxGapSecond()*1000){
                LOG.warn("the fire reason is exceed the gap "+gap+" window instance id is "+windowInstanceId);
                return true;
            }
            return false;
        }

        return true;
    }

    @Override
    public Object doMessage(IMessage message, AbstractContext context) {
        return null;
    }


    protected class WindowInstanceCache extends AbstractMutilSplitMessageCache<WindowInstance>{

        public WindowInstanceCache() {
            super(new IMessageFlushCallBack<WindowInstance>() {
                @Override
                public  boolean flushMessage(List<WindowInstance> windowInstances) {

                    Collections.sort(windowInstances, new Comparator<WindowInstance>() {
                        @Override
                        public int compare(WindowInstance o1, WindowInstance o2) {
                            int value= o1.getFireTime().compareTo(o2.getFireTime());
                            if(value!=0){
                                return value;
                            }
                            return o2.getStartTime().compareTo(o1.getStartTime());
                        }
                    });

                    for(WindowInstance windowInstance:windowInstances){
                        fireWindowInstance(windowInstance);
                    }
                    return true;
                }
            });
        }


        @Override
        protected String createSplitId(WindowInstance windowInstance) {
            return windowInstance.getSplitId();
        }
    }

    @Override
    public boolean supportNewSplitFind() {
        return true;
    }

    @Override
    public boolean supportRemoveSplitFind() {
        return false;
    }

    @Override
    public boolean supportOffsetRest() {
        return false;
    }

    @Override
    protected boolean isNotDataSplit(String queueId) {
        return false;
    }
}
