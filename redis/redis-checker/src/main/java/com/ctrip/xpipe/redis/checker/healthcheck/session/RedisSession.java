package com.ctrip.xpipe.redis.checker.healthcheck.session;

import com.ctrip.framework.xpipe.redis.ProxyRegistry;
import com.ctrip.xpipe.api.command.CommandFuture;
import com.ctrip.xpipe.api.command.CommandFutureListener;
import com.ctrip.xpipe.api.endpoint.Endpoint;
import com.ctrip.xpipe.api.pool.SimpleObjectPool;
import com.ctrip.xpipe.netty.commands.NettyClient;
import com.ctrip.xpipe.pool.XpipeNettyClientKeyedObjectPool;
import com.ctrip.xpipe.redis.checker.config.CheckerConfig;
import com.ctrip.xpipe.redis.core.protocal.LoggableRedisCommand;
import com.ctrip.xpipe.redis.core.protocal.cmd.*;
import com.ctrip.xpipe.redis.core.protocal.cmd.pubsub.*;
import com.ctrip.xpipe.redis.core.protocal.pojo.RedisInfo;
import com.ctrip.xpipe.redis.core.protocal.pojo.Role;
import com.ctrip.xpipe.utils.VisibleForTesting;
import com.google.common.collect.Sets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;
import java.util.function.Supplier;


/**
 * @author marsqing
 *         <p>
 *         Dec 1, 2016 2:28:43 PM
 */
public class RedisSession {

    private static Logger logger = LoggerFactory.getLogger(RedisSession.class);

    public static final String KEY_REDISSESSION_COMMAND_TIMEOUT= "KEY_REDISSESSION_COMMAND_TIMEOUT";

    private int waitResultSeconds = 2;

    private Endpoint endpoint;

    private ConcurrentMap<Set<String>, PubSubConnectionWrapper> subscribConns = new ConcurrentHashMap<>();

    private ScheduledExecutorService scheduled;

    private SimpleObjectPool<NettyClient> clientPool;

    private CheckerConfig config;

    private int commandTimeOut = Integer.parseInt(System.getProperty(KEY_REDISSESSION_COMMAND_TIMEOUT, String.valueOf(AbstractRedisCommand.DEFAULT_REDIS_COMMAND_TIME_OUT_MILLI)));

    public RedisSession(Endpoint endpoint, ScheduledExecutorService scheduled,
                        XpipeNettyClientKeyedObjectPool keyedObjectPool, CheckerConfig config) {
        this.endpoint = endpoint;
        this.scheduled = scheduled;
        this.clientPool = keyedObjectPool.getKeyPool(endpoint);
        this.config = config;
        if(ProxyRegistry.getProxy(endpoint.getHost(), endpoint.getPort()) != null) {
            commandTimeOut = AbstractRedisCommand.PROXYED_REDIS_CONNECTION_COMMAND_TIME_OUT_MILLI;
        }
        logger.info("session command timeout {}:{} {}", endpoint.getHost(), endpoint.getPort(), commandTimeOut);
    }

    public RedisSession() {
    }

    public void check() {

        for (Map.Entry<Set<String>, PubSubConnectionWrapper> entry : subscribConns.entrySet()) {

            Set<String> channel = entry.getKey();
            PubSubConnectionWrapper pubSubConnectionWrapper = entry.getValue();

            if (System.currentTimeMillis() - pubSubConnectionWrapper.getLastActiveTime() > config.subscribeTimeoutMilli()) {

                logger.info("[check][connection inactive for a long time, force reconnect]{}, {}", channel, endpoint);
                pubSubConnectionWrapper.closeAndClean();
                subscribConns.remove(channel);

                String[] channelArray = channel.toArray(new String[0]);
                Subscribe command = pubSubConnectionWrapper.command.get();
                if (command instanceof CRDTSubscribeCommand) {
                    crdtsubscribeIfAbsent(pubSubConnectionWrapper.getCallback(), channelArray);
                } else {
                    subscribeIfAbsent(pubSubConnectionWrapper.getCallback(), channelArray);
                }
            }
        }

    }

    public synchronized void closeSubscribedChannel(String... channel) {

        PubSubConnectionWrapper pubSubConnectionWrapper = subscribConns.get(Sets.newHashSet(channel));
        if (pubSubConnectionWrapper != null) {
            logger.debug("[closeSubscribedChannel]{}, {}", endpoint, channel);
            pubSubConnectionWrapper.closeAndClean();
            subscribConns.remove(Sets.newHashSet(channel));
        }
    }

    public synchronized void subscribeIfAbsent(SubscribeCallback callback, String... channel) {
        subscribeIfAbsent(callback, () -> new SubscribeCommand(clientPool, scheduled, commandTimeOut, channel), channel);
    }

    public synchronized void crdtsubscribeIfAbsent(SubscribeCallback callback, String... channel) {
        subscribeIfAbsent(callback, () -> new CRDTSubscribeCommand(clientPool, scheduled, commandTimeOut, channel), channel);
    }

    private synchronized void subscribeIfAbsent(SubscribeCallback callback, Supplier<Subscribe> subCommandSupplier, String... channel) {
        PubSubConnectionWrapper pubSubConnectionWrapper = subscribConns.get(Sets.newHashSet(channel));
        if (pubSubConnectionWrapper == null || pubSubConnectionWrapper.shouldCreateNewSession()) {
            if(pubSubConnectionWrapper != null) {
                pubSubConnectionWrapper.closeAndClean();
            }
            Subscribe command = subCommandSupplier.get();

            silentCommand(command);
            command.future().addListener(new CommandFutureListener<Object>() {
                @Override
                public void operationComplete(CommandFuture<Object> commandFuture) throws Exception {
                    subscribConns.remove(Sets.newHashSet(channel));
                }
            });
            PubSubConnectionWrapper wrapper = new PubSubConnectionWrapper(command, callback, subCommandSupplier);
            subscribConns.put(Sets.newHashSet(channel), wrapper);
        } else {
            pubSubConnectionWrapper.replace(callback);
        }
    }

    public synchronized void publish(String channel, String message) {
        PublishCommand pubCommand = new PublishCommand(clientPool, scheduled, commandTimeOut, channel, message);
        silentCommand(pubCommand);

        pubCommand.execute().addListener(new CommandFutureListener<Object>() {
            @Override
            public void operationComplete(CommandFuture<Object> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    logger.error("Error publish to redis {}, {}", endpoint, commandFuture.cause());
                }
            }
        });
    }

    public synchronized void crdtpublish(String channel, String message) {
        CRDTPublishCommand pubCommand = new CRDTPublishCommand(clientPool, scheduled, commandTimeOut, channel, message);
        silentCommand(pubCommand);

        pubCommand.execute().addListener(new CommandFutureListener<Object>() {
            @Override
            public void operationComplete(CommandFuture<Object> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    logger.error("Error crdtpublish to redis {}, {}", endpoint, commandFuture.cause());
                }
            }
        });
    }

    public CommandFuture<String> ping(final PingCallback callback) {
        // if connect has been established
        PingCommand pingCommand = new PingCommand(clientPool, scheduled, commandTimeOut);
        silentCommand(pingCommand);

        pingCommand.execute().addListener(new CommandFutureListener<String>() {
            @Override
            public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                if(commandFuture.isSuccess()) {
                    callback.pong(commandFuture.get());
                } else {
                    callback.fail(commandFuture.cause());
                }
            }
        });
        return pingCommand.future();
    }

    public CommandFuture<Role> role(RollCallback callback) {
        RoleCommand command = new RoleCommand(clientPool, commandTimeOut, false, scheduled);
        silentCommand(command);
        command.execute().addListener(new CommandFutureListener<Role>() {
            @Override
            public void operationComplete(CommandFuture<Role> commandFuture) throws Exception {
                if(commandFuture.isSuccess()) {
                    callback.role(commandFuture.get().getServerRole().name(), commandFuture.get());
                } else {
                    callback.fail(commandFuture.cause());
                }
            }
        });
        return command.future();
    }

    public void configRewrite(BiConsumer<String, Throwable> consumer) {
        ConfigRewrite command = new ConfigRewrite(clientPool, scheduled, commandTimeOut);
        silentCommand(command);
        command.execute().addListener(new CommandFutureListener<String>() {
            @Override
            public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                if(commandFuture.isSuccess()) {
                    consumer.accept(commandFuture.get(), null);
                } else {
                    consumer.accept(null, commandFuture.cause());
                }
            }
        });

    }

    public String roleSync() throws InterruptedException, ExecutionException, TimeoutException {

        RoleCommand command = new RoleCommand(clientPool, waitResultSeconds * 1000, true, scheduled);
        silentCommand(command);
        return command.execute().get().getServerRole().name();

    }

    private <V> CommandFuture<V>  addHookAndExecute(AbstractRedisCommand<V> command, Callbackable<V> callback) {
        silentCommand(command);
        CommandFuture<V> future = command.execute();
        future.addListener(new CommandFutureListener<V>() {
            @Override
            public void operationComplete(CommandFuture<V> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    callback.fail(commandFuture.cause());
                } else {
                    callback.success(commandFuture.get());
                }
            }
        });
        return future;
    }

    public CommandFuture<Long> expireSize(Callbackable<Long> callback) {
        ExpireSizeCommand command = new ExpireSizeCommand(clientPool, scheduled, commandTimeOut);
        return addHookAndExecute(command, callback);
    }

    public CommandFuture<Long> tombstoneSize(Callbackable<Long> callback) {
        TombstoneSizeCommand command = new TombstoneSizeCommand(clientPool, scheduled, commandTimeOut);
        return addHookAndExecute(command, callback);
    }

    public CommandFuture<String> info(final String infoSection, Callbackable<String> callback) {

        InfoCommand command = new InfoCommand(clientPool, infoSection, scheduled, commandTimeOut);
        return addHookAndExecute(command, callback);
    }

    public CommandFuture<String> crdtInfo(final String infoSection, Callbackable<String> callback) {

        InfoCommand command = new CRDTInfoCommand(clientPool, infoSection, scheduled, commandTimeOut);
        return addHookAndExecute(command, callback);
    }

    public CommandFuture<String> infoStats(Callbackable<String> callback) {
        return info(InfoCommand.INFO_TYPE.STATS.cmd(), callback);
    }


    public CommandFuture<String> infoReplication(Callbackable<String> callback) {
        return info(InfoCommand.INFO_TYPE.REPLICATION.cmd(), callback);
    }

    public CommandFuture infoServer(Callbackable<String> callback) {
        String section = "server";
        return info(section, callback);
    }

    public CommandFuture<String> crdtInfoStats(Callbackable<String> callback) {
        return crdtInfo(InfoCommand.INFO_TYPE.STATS.cmd(), callback);
    }

    public CommandFuture<String> crdtInfoReplication(Callbackable<String> callback) {
        return crdtInfo(InfoCommand.INFO_TYPE.REPLICATION.cmd(), callback);
    }

    public void isDiskLessSync(Callbackable<Boolean> callback) {
        ConfigGetCommand.ConfigGetDisklessSync command = new ConfigGetCommand.ConfigGetDisklessSync(clientPool, scheduled, commandTimeOut);
        silentCommand(command);
        command.execute().addListener(new CommandFutureListener<Boolean>() {

            @Override
            public void operationComplete(CommandFuture<Boolean> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    callback.fail(commandFuture.cause());
                } else {
                    callback.success(commandFuture.get());
                }
            }
        });
    }

    public void ConfigGet(Callbackable<String> callback, String args) {
        ConfigGetCommand.ConfigGetAnyCommand command = new ConfigGetCommand.ConfigGetAnyCommand(clientPool, scheduled, args);
        silentCommand(command);

        command.execute().addListener(new CommandFutureListener<String>() {
            @Override
            public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    callback.fail(commandFuture.cause());
                } else {
                    callback.success(commandFuture.get());
                }
            }
        });
    }

    public void CRDTConfigGet(Callbackable<String> callback, String args) {
        CRDTConfigGetCommand command = new CRDTConfigGetCommand(clientPool, scheduled, args);
        silentCommand(command);

        command.execute().addListener(new CommandFutureListener<String>() {
            @Override
            public void operationComplete(CommandFuture<String> commandFuture) throws Exception {
                if(!commandFuture.isSuccess()) {
                    callback.fail(commandFuture.cause());
                } else {
                    callback.success(commandFuture.get());
                }
            }
        });
    }


    public InfoResultExtractor syncInfo(InfoCommand.INFO_TYPE infoType)
            throws InterruptedException, ExecutionException, TimeoutException {
        InfoCommand infoCommand = new InfoCommand(clientPool, infoType, scheduled);
        String info = infoCommand.execute().get(2000, TimeUnit.MILLISECONDS);
        return new InfoResultExtractor(info);
    }

    public InfoResultExtractor syncCRDTInfo(InfoCommand.INFO_TYPE infoType) throws InterruptedException, ExecutionException, TimeoutException {
        CRDTInfoCommand command = new CRDTInfoCommand(clientPool, infoType, scheduled);
        String info = command.execute().get(2000, TimeUnit.MILLISECONDS);
        return new InfoResultExtractor(info);
    }


    public CommandFuture<RedisInfo> getRedisReplInfo() {
        InfoReplicationCommand command = new InfoReplicationCommand(clientPool, scheduled, commandTimeOut);
        silentCommand(command);
        return command.execute();
    }

    private void silentCommand(LoggableRedisCommand command) {
        command.logRequest(false);
        command.logResponse(false);

    }

    @Override
    public String toString() {
        if (endpoint != null) {
            return String.format("%s", endpoint);
        }
        return "";
    }

    @VisibleForTesting
    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public interface RollCallback {

        void role(String role, Role detail);

        void fail(Throwable e);
    }

    public interface SubscribeCallback {

        void message(String channel, String message);

        void fail(Throwable e);
    }

    public class PubSubConnectionWrapper {

        private Long lastActiveTime = System.currentTimeMillis();
        private AtomicReference<SubscribeCallback> callback = new AtomicReference<>();
        private AtomicReference<CommandFuture> subscribeCommandFuture = new AtomicReference<>();
        private AtomicReference<Subscribe> command = new AtomicReference<>();
        private AtomicReference<Supplier<Subscribe>> supplier = new AtomicReference<>();


        public PubSubConnectionWrapper(Subscribe command, SubscribeCallback callback, Supplier<Subscribe> commandSupplier) {
            this.command.set(command);
            this.callback.set(callback);
            this.supplier.set(commandSupplier);
            command.addChannelListener(new SubscribeListener() {
                @Override
                public void message(String channel, String message) {
                    setLastActiveTime(System.currentTimeMillis());
                    getCallback().message(channel, message);
                }
            });

            command.future().addListener(new CommandFutureListener<Object>() {
                @Override
                public void operationComplete(CommandFuture<Object> commandFuture) throws Exception {
                    if(!commandFuture.isSuccess()) {
                        getCallback().fail(commandFuture.cause());
                    }
                }
            });
            silentCommand(command);
            CommandFuture commandFuture = command.execute();
            this.subscribeCommandFuture.set(commandFuture);
        }

        public void closeAndClean() {
            command.get().unSubscribe();
            if(!getSubscribeCommandFuture().isDone()) {
                subscribeCommandFuture.get().cancel(true);
            }
        }

        public CommandFuture getSubscribeCommandFuture() {
            return subscribeCommandFuture.get();
        }

        public SubscribeCallback getCallback() {
            return callback.get();
        }

        public Supplier<Subscribe> getSubCommandSupplier() {
            return supplier.get();
        }

        public void replace(SubscribeCallback callback) {
            this.callback.set(callback);

        }

        public void setLastActiveTime(Long lastActiveTime) {
            this.lastActiveTime = lastActiveTime;
        }

        public Long getLastActiveTime() {
            return lastActiveTime;
        }

        public boolean shouldCreateNewSession() {
            return getSubscribeCommandFuture().isDone();
        }
    }

    public void closeConnection() {
        for(PubSubConnectionWrapper connectionWrapper : subscribConns.values()) {
            try {
                connectionWrapper.closeAndClean();
            } catch (Exception ignore) {}
        }
        try {
            clientPool.clear();
        } catch (Throwable th) {
            logger.info("[closeConnection][{}] fail", endpoint, th);
        }
    }

    public RedisSession setKeyedNettyClientPool(XpipeNettyClientKeyedObjectPool keyedNettyClientPool) {
        this.clientPool = keyedNettyClientPool.getKeyPool(endpoint);
        return this;
    }

    public int getCommandTimeOut() {
        return commandTimeOut;
    }
}
