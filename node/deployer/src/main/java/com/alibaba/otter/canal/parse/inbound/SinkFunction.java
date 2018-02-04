package com.alibaba.otter.canal.parse.inbound;

import com.alibaba.otter.canal.protocol.CanalEntry;

import java.util.List;

/**
 * receive parsed bytes , 用于处理要解析的数据块
 *
 * @author: yuanzu Date: 12-9-20 Time: 下午2:50
 */

public interface SinkFunction<EVENT> {

    public boolean sinkEntry(CanalEntry.Entry entry);
    public boolean sink(EVENT event);

    /**
     * 进行批量解析
     * @param events
     * @param batchCount
     * @return
     */
    public List<CanalEntry.Entry> parse(List<EVENT> events,int batchCount);
}
