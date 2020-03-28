/*
 *  Copyright 1999-2019 Seata.io Group.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package io.seata.core.rpc.netty;

import io.netty.channel.Channel;
import io.seata.common.exception.FrameworkErrorCode;
import io.seata.common.exception.FrameworkException;
import io.seata.common.util.CollectionUtils;
import io.seata.common.util.NetUtil;
import io.seata.core.protocol.RegisterRMRequest;
import io.seata.discovery.registry.RegistryFactory;
import org.apache.commons.pool.impl.GenericKeyedObjectPool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 *
 * 客户端池管理器
 * Netty client pool manager.
 *
 * @author slievrly
 * @author zhaojun
 */
class NettyClientChannelManager {
    
    private static final Logger LOGGER = LoggerFactory.getLogger(NettyClientChannelManager.class);
    
    private final ConcurrentMap<String, Object> channelLocks = new ConcurrentHashMap<>();
    
    private final ConcurrentMap<String, NettyPoolKey> poolKeyMap = new ConcurrentHashMap<>();
    
    private final ConcurrentMap<String, Channel> channels = new ConcurrentHashMap<>();

    //客户端连接池 使用common-pool
    private final GenericKeyedObjectPool<NettyPoolKey, Channel> nettyClientKeyPool;
    
    private Function<String, NettyPoolKey> poolKeyFunction;
    
    NettyClientChannelManager(final NettyPoolableFactory keyPoolableFactory, final Function<String, NettyPoolKey> poolKeyFunction,
                                     final NettyClientConfig clientConfig) {
        nettyClientKeyPool = new GenericKeyedObjectPool<>(keyPoolableFactory);
        nettyClientKeyPool.setConfig(getNettyPoolConfig(clientConfig));
        this.poolKeyFunction = poolKeyFunction;
    }
    
    private GenericKeyedObjectPool.Config getNettyPoolConfig(final NettyClientConfig clientConfig) {
        GenericKeyedObjectPool.Config poolConfig = new GenericKeyedObjectPool.Config();
        poolConfig.maxActive = clientConfig.getMaxPoolActive();
        poolConfig.minIdle = clientConfig.getMinPoolIdle();
        poolConfig.maxWait = clientConfig.getMaxAcquireConnMills();
        poolConfig.testOnBorrow = clientConfig.isPoolTestBorrow();
        poolConfig.testOnReturn = clientConfig.isPoolTestReturn();
        poolConfig.lifo = clientConfig.isPoolLifo();
        return poolConfig;
    }
    
    /**
     * Get all channels registered on current Rpc Client.
     *
     * @return channels
     */
    ConcurrentMap<String, Channel> getChannels() {
        return channels;
    }
    
    /**
     * Acquire netty client channel connected to remote server.
     *
     * @param serverAddress server address
     * @return netty channel
     */
    Channel acquireChannel(String serverAddress) {
        Channel channelToServer = channels.get(serverAddress);
        if (channelToServer != null) {
            channelToServer = getExistAliveChannel(channelToServer, serverAddress);
            if (null != channelToServer) {
                return channelToServer;
            }
        }
        if (LOGGER.isInfoEnabled()) {
            LOGGER.info("will connect to " + serverAddress);
        }
        channelLocks.putIfAbsent(serverAddress, new Object());
        //没有存活的Channel重新连接
        synchronized (channelLocks.get(serverAddress)) {
            return doConnect(serverAddress);
        }
    }
    
    /**
     * Release channel to pool if necessary.
     *
     * @param channel channel
     * @param serverAddress server address
     */
    void releaseChannel(Channel channel, String serverAddress) {
        if (null == channel || null == serverAddress) { return; }
        try {
            synchronized (channelLocks.get(serverAddress)) {
                Channel ch = channels.get(serverAddress);
                if (null == ch) {
                    nettyClientKeyPool.returnObject(poolKeyMap.get(serverAddress), channel);
                    return;
                }
                if (ch.compareTo(channel) == 0) {
                    if (LOGGER.isInfoEnabled()) {
                        LOGGER.info("return to pool, rm channel:{}", channel);
                    }
                    destroyChannel(serverAddress, channel);
                } else {
                    nettyClientKeyPool.returnObject(poolKeyMap.get(serverAddress), channel);
                }
            }
        } catch (Exception exx) {
            LOGGER.error(exx.getMessage());
        }
    }
    
    /**
     * Destroy channel.
     *
     * @param serverAddress server address
     * @param channel channel
     */
    void destroyChannel(String serverAddress, Channel channel) {
        if (null == channel) { return; }
        try {
            if (channel.equals(channels.get(serverAddress))) {
                channels.remove(serverAddress);
            }
            nettyClientKeyPool.returnObject(poolKeyMap.get(serverAddress), channel);
        } catch (Exception exx) {
            LOGGER.error("return channel to rmPool error:{}", exx.getMessage());
        }
    }
    
    /**
     * Reconnect to remote server of current transaction service group.
     *
     * @param transactionServiceGroup transaction service group
     */
    void reconnect(String transactionServiceGroup) {
        List<String> availList = null;
        try {
            //获取服务地址
            availList = getAvailServerList(transactionServiceGroup);
        } catch (Exception e) {
            LOGGER.error("Failed to get available servers: {}", e.getMessage(), e);
            return;
        }
        if (CollectionUtils.isEmpty(availList)) {
            //没有可用服务列表
            String serviceGroup = RegistryFactory.getInstance()
                                                 .getServiceGroup(transactionServiceGroup);
            LOGGER.error("no available service '{}' found, please make sure registry config correct", serviceGroup);
            return;
        }
        for (String serverAddress : availList) {
            try {
                //获取可用的Channel
                acquireChannel(serverAddress);
            } catch (Exception e) {
                LOGGER.error("{} can not connect to {} cause:{}",FrameworkErrorCode.NetConnect.getErrCode(), serverAddress, e.getMessage(), e);
            }
        }
    }
    
    void invalidateObject(final String serverAddress, final Channel channel) throws Exception {
        nettyClientKeyPool.invalidateObject(poolKeyMap.get(serverAddress), channel);
    }
    
    void registerChannel(final String serverAddress, final Channel channel) {
        if (null != channels.get(serverAddress) && channels.get(serverAddress).isActive()) {
            return;
        }
        channels.put(serverAddress, channel);
    }

    /***
     *
     * 从对象池中获取channel对象
     *
     * @author liyong
     * @date 23:25 2020-03-28
     * @param serverAddress
     * @exception
     * @return io.netty.channel.Channel
     **/
    private Channel doConnect(String serverAddress) {
        Channel channelToServer = channels.get(serverAddress);
        if (channelToServer != null && channelToServer.isActive()) {
            return channelToServer;
        }
        Channel channelFromPool;
        try {
            //产生一个NettyPoolKey
            NettyPoolKey currentPoolKey = poolKeyFunction.apply(serverAddress);
            NettyPoolKey previousPoolKey = poolKeyMap.putIfAbsent(serverAddress, currentPoolKey);
            if (null != previousPoolKey && previousPoolKey.getMessage() instanceof RegisterRMRequest) {
                RegisterRMRequest registerRMRequest = (RegisterRMRequest) currentPoolKey.getMessage();
                ((RegisterRMRequest) previousPoolKey.getMessage()).setResourceIds(registerRMRequest.getResourceIds());
            }
            //从池中获取一个对象
            channelFromPool = nettyClientKeyPool.borrowObject(poolKeyMap.get(serverAddress));
            channels.put(serverAddress, channelFromPool);
        } catch (Exception exx) {
            LOGGER.error("{} register RM failed.",FrameworkErrorCode.RegisterRM.getErrCode(), exx);
            throw new FrameworkException("can not register RM,err:" + exx.getMessage());
        }
        return channelFromPool;
    }
    
    private List<String> getAvailServerList(String transactionServiceGroup) throws Exception {
        //从注册中心获取服务地址
        List<InetSocketAddress> availInetSocketAddressList = RegistryFactory.getInstance()
                                                                            .lookup(transactionServiceGroup);
        if (CollectionUtils.isEmpty(availInetSocketAddressList)) {
            return Collections.emptyList();
        }

        return availInetSocketAddressList.stream()
                                         .map(NetUtil::toStringAddress)
                                         .collect(Collectors.toList());
    }

    /***
     *
     * 查找存活的通道
     *
     * @author liyong
     * @date 00:03 2020-03-27
     * @param rmChannel
     * @param serverAddress
     * @exception
     * @return io.netty.channel.Channel
     **/
    private Channel getExistAliveChannel(Channel rmChannel, String serverAddress) {
        if (rmChannel.isActive()) {
            return rmChannel;
        } else {
            int i = 0;
            for (; i < NettyClientConfig.getMaxCheckAliveRetry(); i++) {
                try {
                    Thread.sleep(NettyClientConfig.getCheckAliveInternal());
                } catch (InterruptedException exx) {
                    LOGGER.error(exx.getMessage());
                }
                rmChannel = channels.get(serverAddress);
                if (null != rmChannel && rmChannel.isActive()) {
                    return rmChannel;
                }
            }
            //达到最大等待次数，则关闭通道
            if (i == NettyClientConfig.getMaxCheckAliveRetry()) {
                LOGGER.warn("channel {} is not active after long wait, close it.", rmChannel);
                releaseChannel(rmChannel, serverAddress);
                return null;
            }
        }
        return null;
    }
}

