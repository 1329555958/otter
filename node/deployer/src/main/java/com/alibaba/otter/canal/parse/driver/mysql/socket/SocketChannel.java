package com.alibaba.otter.canal.parse.driver.mysql.socket;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketAddress;

/**
 * 封装netty的通信channel和数据接收缓存，实现读、写、连接校验的功能。 2016-12-28
 *
 * @author luoyaogui
 */
public class SocketChannel {
    static Logger log = LoggerFactory.getLogger(SocketChannel.class);
    private Channel channel = null;
    private Object lock = new Object();
    private ByteBuf cache = PooledByteBufAllocator.DEFAULT.directBuffer(1024 * 1024, 1024 * 1024 * 128); // 缓存大小

    public Channel getChannel() {
        return channel;
    }

    public void setChannel(Channel channel) {
        this.channel = channel;
        log.error("channel setted is null ? {}", channel == null, new RuntimeException("set channel"));
    }

    public void writeCache(ByteBuf buf) {
        //达到最大容量
        if (cache.capacity() == cache.maxCapacity()) {
            while (true) {
                if (cache.writableBytes() < buf.readableBytes()) {
                    synchronized (lock) {
                        log.warn("{} no space to write ,to discard cache", cache);
                        cache.discardReadBytes();
                        log.warn("after discard cache {}", cache);
                    }
                    //回收内存之后依然不够写就等一会继续回收
                    if (cache.writableBytes() < buf.readableBytes()) {
                        try {
                            log.warn("lack of processing ability,cache is full {}", cache);
                            Thread.sleep(100);
                        } catch (InterruptedException e) {
                            log.error("{} has no enough space to write", cache, e);
                        }
                    }
                } else {
                    break;
                }
            }
        }
        synchronized (lock) {
            if (cache.writableBytes() < buf.readableBytes()) { //不够写时再回收
                cache.discardReadBytes();// 回收内存
            }
            cache.writeBytes(buf);
        }
    }

    public void writeChannel(byte[]... buf) throws IOException {
        if (channel != null && channel.isWritable()) {
            channel.writeAndFlush(Unpooled.copiedBuffer(buf));
        } else {
            throw new IOException("write  failed  !  please checking !");
        }
    }

    public byte[] read(int readSize) throws IOException {
        do {
            if (readSize > cache.readableBytes()) {
                if (null == channel) {
                    throw new java.nio.channels.ClosedByInterruptException();
                }
                synchronized (this) {
                    try {
                        wait(100);
                    } catch (InterruptedException e) {
                        throw new java.nio.channels.ClosedByInterruptException();
                    }
                }
            } else {
                byte[] back = new byte[readSize];
                synchronized (lock) {
                    cache.readBytes(back);
                }
                return back;
            }
        } while (true);
    }

    public boolean isConnected() {
        return channel != null ? true : false;
    }

    public SocketAddress getRemoteSocketAddress() {
        return channel != null ? channel.remoteAddress() : null;
    }

    public void close() {
        log.error("channel closed ", new RuntimeException("close channel"));
        if (channel != null) {
            channel.close();
        }
        channel = null;
        cache.discardReadBytes();// 回收已占用的内存
        cache.release();// 释放整个内存
        cache = null;
    }
}
