/*
 * Copyright (C) 2010-2101 Alibaba Group Holding Limited.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.alibaba.otter.node.common.statistics.impl;

import com.alibaba.otter.node.common.communication.NodeCommmunicationClient;
import com.alibaba.otter.node.common.statistics.StatisticsClientService;
import com.alibaba.otter.shared.common.model.statistics.delay.DelayCount;
import com.alibaba.otter.shared.common.model.statistics.table.TableStat;
import com.alibaba.otter.shared.common.model.statistics.throughput.ThroughputStat;
import com.alibaba.otter.shared.common.utils.thread.NamedThreadFactory;
import com.alibaba.otter.shared.communication.core.model.Callback;
import com.alibaba.otter.shared.communication.model.statistics.DelayCountEvent;
import com.alibaba.otter.shared.communication.model.statistics.DelayCountEvent.Action;
import com.alibaba.otter.shared.communication.model.statistics.TableStatEvent;
import com.alibaba.otter.shared.communication.model.statistics.ThroughputStatEvent;
import com.google.common.base.Function;
import com.google.common.collect.Lists;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.locks.LockSupport;

/**
 * 统计信息的本地客户端服务
 * 
 * @author jianghang
 */
public class StatisticsClientServiceImpl implements StatisticsClientService, InitializingBean {

    private static final Logger                logger                = LoggerFactory.getLogger(StatisticsClientServiceImpl.class);
    private static final int                   DEFAULT_POOL          = 10;
    // 使用一个buffer队列，保证inc/desc/reset的发送操作为一个串行过程
    private BlockingQueue<DelayCountEvent>     delayCountStatsBuffer = new LinkedBlockingQueue<DelayCountEvent>(
                                                                                                                10 * 1000);
    private static ScheduledThreadPoolExecutor scheduler;
    private NodeCommmunicationClient           nodeCommmunicationClient;

    private EventQueue eventQueue ;
    public void sendIncDelayCount(final DelayCount delayCount) {
        DelayCountEvent event = new DelayCountEvent();
        event.setCount(delayCount);
        event.setAction(Action.INC);

        boolean result = delayCountStatsBuffer.offer(event);
        if (result) {
            logger.info("add IncDelayCount to send with {}", delayCount);
        } else {
            logger.warn("add IncDelayCount failed by buffer is full with {}", delayCount);
        }
    }

    public void sendDecDelayCount(final DelayCount delayCount) {
        DelayCountEvent event = new DelayCountEvent();
        event.setCount(delayCount);
        event.setAction(Action.DEC);

        boolean result = delayCountStatsBuffer.offer(event);
        if (result) {
            logger.info("add sendDecDelayCount to send with {}", delayCount);
        } else {
            logger.warn("add sendDecDelayCount failed by buffer is full with {}", delayCount);
        }
    }

    public void sendResetDelayCount(final DelayCount delayCount) {
        DelayCountEvent event = new DelayCountEvent();
        event.setCount(delayCount);
        event.setAction(Action.RESET);

        boolean result = delayCountStatsBuffer.offer(event);
        if (result) {
            logger.info("add sendResetDelayCount to send with {}", delayCount);
        } else {
            logger.warn("add sendResetDelayCount failed by buffer is full with {}", delayCount);
        }
    }

    public void sendThroughputs(final List<ThroughputStat> stats) {
        eventQueue.pushThroughput(stats);
    }

    public void sendTableStats(final List<TableStat> stats) {
        eventQueue.pushTablestat(stats);
    }

    // ================= helper method ==============
    public void afterPropertiesSet() throws Exception {
        scheduler = new ScheduledThreadPoolExecutor(DEFAULT_POOL, new NamedThreadFactory("Otter-Statistics-Client"),
                                                    new ThreadPoolExecutor.CallerRunsPolicy());
        scheduler.submit(new Runnable() {

            public void run() {
                doSendDelayCountEvent();
            }
        });
        eventQueue = new EventQueue();
        scheduler.scheduleAtFixedRate(eventQueue,60,60,TimeUnit.SECONDS);
    }

    private void doSendDelayCountEvent() {
        DelayCountEvent event = null;
        while (true) { // 尝试从队列里获取一下数据，不阻塞，没有就退出，等下个5秒再检查一次
            try {
                event = delayCountStatsBuffer.take();
                nodeCommmunicationClient.callManager(event);
                logger.info("sendDelayCountEvent successed for {}", event);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return; // 退出
            } catch (Exception e) {
                LockSupport.parkNanos(TimeUnit.NANOSECONDS.convert(1, TimeUnit.SECONDS));
            }
        }
    }

    // ================ setter / getter =================

    public void setNodeCommmunicationClient(NodeCommmunicationClient nodeCommmunicationClient) {
        this.nodeCommmunicationClient = nodeCommmunicationClient;
    }

    class EventQueue implements Runnable {
        private final DateFormat MIN_FORMAT = new SimpleDateFormat("yyyyMMddHHmm");
        private Object throughputLock = new Object(), tableLock = new Object();

        private List<ThroughputStat> throughputStats = new ArrayList<ThroughputStat>();
        private List<TableStat> tableStats = new ArrayList<TableStat>();

        public void pushThroughput(List<ThroughputStat> stats) {
            synchronized (throughputLock) {
                throughputStats.addAll(stats);
            }
        }

        public void pushTablestat(List<TableStat> stats) {
            synchronized (tableLock) {
                tableStats.addAll(stats);
            }
        }
        //把列表按照一定的规则分组
        private <E> Map<Object,List<E>> groupList(List<E> datas,Function<E,Object> keyFunction){
            Map<Object,List<E>> map = new HashMap<Object, List<E>>();
            for (E data : datas) {
                Object key = keyFunction.apply(data);
                List<E> list = map.get(key);
                if(list == null){
                    list = new ArrayList<E>();
                    map.put(key,list);
                }
                list.add(data);
            }
            return map;
        }
        //把分组数据合并
        private <E> List<E> mergeMap(Map<Object,List<E>> map,Function<List<E>,E> mergeFunction){
            if(map == null || map.size() == 0){
                return null;
            }
            List<E> list = new ArrayList<E>();
            for (List<E> es : map.values()) {
                list.add(mergeFunction.apply(es));
            }
            return list;
        }
        private void sendThroughputStats() {
            List<ThroughputStat> thoughput = null;
            synchronized (throughputLock) {
                thoughput = throughputStats;
                throughputStats = new ArrayList<ThroughputStat>();
            }
            Map<Object,List<ThroughputStat>> statGroup = groupList(thoughput, new Function<ThroughputStat, Object>() {
                @Override
                public String apply(ThroughputStat input) {
                    //开始时间在同一分钟的算一组
                    return new StringBuilder().append(input.getPipelineId()).append(input.getType().name()).append(MIN_FORMAT.format(input.getStartTime())).toString();
                }
            });
            thoughput = mergeMap(statGroup, new Function<List<ThroughputStat>, ThroughputStat>() {
                @Override
                public ThroughputStat apply(List<ThroughputStat> input) {
                    ThroughputStat stat = input.get(0);
                    for (int i=1;i<input.size();i++){
                        stat.setNumber(input.get(i).getNumber()+stat.getNumber());
                        stat.setSize(input.get(i).getSize()+stat.getSize());
                    }
                    return stat;
                }
            });
            if(thoughput == null || thoughput.size() == 0){
                return;
            }
            ThroughputStatEvent event = new ThroughputStatEvent();
            event.setStats(thoughput);
            logger.debug("send throughput stats {}", Lists.transform(thoughput, new Function<ThroughputStat, Object>() {
                @Override
                public Object apply(ThroughputStat input) {
                    return MIN_FORMAT.format(input.getStartTime())+"-"+MIN_FORMAT.format(input.getEndTime()) +":"+ input.getNumber();
                }
            }));
            final long start = System.currentTimeMillis();
            nodeCommmunicationClient.callManager(event, new Callback<Object>() {
                public void call(Object event) {
                    logger.warn("send throughput stats took {} ms,result is {}", System.currentTimeMillis() - start,event);
                }
            });
        }
        private void sendTableStats() {
            List<TableStat> table = null;
            synchronized (tableLock){
                table = tableStats;
                tableStats = new ArrayList<TableStat>();
            }
            Map<Object,List<TableStat>> map = groupList(table, new Function<TableStat, Object>() {
                @Override
                public Object apply(TableStat input) {
                    return new StringBuilder().append(input.getPipelineId()).append(input.getDataMediaPairId()).toString();
                }
            });
            table = mergeMap(map, new Function<List<TableStat>, TableStat>() {
                @Override
                public TableStat apply(List<TableStat> input) {
                    TableStat stat = input.get(0),next = null;
                    for (int i = 1;i<input.size();i++){
                        next = input.get(i);
                        stat.setInsertCount(stat.getInsertCount() + next.getInsertCount());
                        stat.setUpdateCount(stat.getUpdateCount() + next.getUpdateCount());
                        stat.setDeleteCount(stat.getDeleteCount() + next.getDeleteCount());
                        stat.setFileCount(stat.getFileCount() + next.getFileCount());
                        stat.setFileSize(next.getFileSize());
                    }
                    return stat;
                }
            });
            if(table == null || table.size() == 0){
                return;
            }
            TableStatEvent event = new TableStatEvent();
            event.setStats(table);
            logger.debug("===== send {} table stats ,{}",table.size(),table);
            final long start = System.currentTimeMillis();
            nodeCommmunicationClient.callManager(event, new Callback<Object>() {
                public void call(Object event) {
                    logger.info("send table stats took {}ms,result is {}", System.currentTimeMillis() - start,event);
                }
            });
        }

        @Override
        public void run() {
            try {
                sendThroughputStats();
                sendTableStats();
            }catch (Exception e){
                logger.error("xxxxxxxxxx send stats error",e);
            }
        }
    }
}