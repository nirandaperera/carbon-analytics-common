/*
 * Copyright (c) 2005-2015, WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.wso2.carbon.event.output.adapter.websocket.local;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.wso2.carbon.context.CarbonContext;
import org.wso2.carbon.event.output.adapter.core.EventAdapterUtil;
import org.wso2.carbon.event.output.adapter.core.OutputEventAdapter;
import org.wso2.carbon.event.output.adapter.core.OutputEventAdapterConfiguration;
import org.wso2.carbon.event.output.adapter.core.exception.OutputEventAdapterException;
import org.wso2.carbon.event.output.adapter.core.exception.TestConnectionNotSupportedException;
import org.wso2.carbon.event.output.adapter.websocket.local.internal.WebsocketLocalOutputCallbackRegisterServiceImpl;
import org.wso2.carbon.event.output.adapter.websocket.local.internal.ds.WebsocketLocalEventAdaptorServiceInternalValueHolder;
import org.wso2.carbon.event.output.adapter.websocket.local.internal.util.WebsocketLocalEventAdapterConstants;

import javax.websocket.Session;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.*;

public final class WebsocketLocalEventAdapter implements OutputEventAdapter {

    private static final Log log = LogFactory.getLog(WebsocketLocalEventAdapter.class);
    private OutputEventAdapterConfiguration eventAdapterConfiguration;
    private Map<String, String> globalProperties;
    private static ThreadPoolExecutor executorService;

    private int tenantId;

    public WebsocketLocalEventAdapter(OutputEventAdapterConfiguration eventAdapterConfiguration, Map<String, String> globalProperties) {
        this.eventAdapterConfiguration = eventAdapterConfiguration;
        this.globalProperties = globalProperties;
    }


    @Override
    public void init() throws OutputEventAdapterException {

        tenantId = CarbonContext.getThreadLocalCarbonContext().getTenantId();

        //ExecutorService will be assigned  if it is null
        if (executorService == null) {
            int minThread;
            int maxThread;
            long defaultKeepAliveTime;
            int jobQueSize;

            //If global properties are available those will be assigned else constant values will be assigned
            if (globalProperties.get(WebsocketLocalEventAdapterConstants.ADAPTER_MIN_THREAD_POOL_SIZE_NAME) != null) {
                minThread = Integer.parseInt(globalProperties.get(
                        WebsocketLocalEventAdapterConstants.ADAPTER_MIN_THREAD_POOL_SIZE_NAME));
            } else {
                minThread = WebsocketLocalEventAdapterConstants.ADAPTER_MIN_THREAD_POOL_SIZE;
            }

            if (globalProperties.get(WebsocketLocalEventAdapterConstants.ADAPTER_MAX_THREAD_POOL_SIZE_NAME) != null) {
                maxThread = Integer.parseInt(globalProperties.get(
                        WebsocketLocalEventAdapterConstants.ADAPTER_MAX_THREAD_POOL_SIZE_NAME));
            } else {
                maxThread = WebsocketLocalEventAdapterConstants.ADAPTER_MAX_THREAD_POOL_SIZE;
            }

            if (globalProperties.get(WebsocketLocalEventAdapterConstants.ADAPTER_KEEP_ALIVE_TIME_NAME) != null) {
                defaultKeepAliveTime = Integer.parseInt(globalProperties.get(
                        WebsocketLocalEventAdapterConstants.ADAPTER_KEEP_ALIVE_TIME_NAME));
            } else {
                defaultKeepAliveTime = WebsocketLocalEventAdapterConstants.DEFAULT_KEEP_ALIVE_TIME_IN_MILLIS;
            }

            if (globalProperties.get(WebsocketLocalEventAdapterConstants.ADAPTER_EXECUTOR_JOB_QUEUE_SIZE_NAME) != null) {
                jobQueSize = Integer.parseInt(globalProperties.get(
                        WebsocketLocalEventAdapterConstants.ADAPTER_EXECUTOR_JOB_QUEUE_SIZE_NAME));
            } else {
                jobQueSize = WebsocketLocalEventAdapterConstants.ADAPTER_EXECUTOR_JOB_QUEUE_SIZE;
            }

            executorService = new ThreadPoolExecutor(minThread, maxThread, defaultKeepAliveTime, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<Runnable>(jobQueSize));
        }
    }

    @Override
    public void testConnect() throws TestConnectionNotSupportedException {
        //Not applicable.
    }

    @Override
    public void connect() {
        //Nothing to do.
    }

    @Override
    public void publish(Object message, Map<String, String> dynamicProperties) {
        try {
            executorService.execute(new WebSocketSender(message.toString()));
        } catch (RejectedExecutionException e) {
            EventAdapterUtil.logAndDrop(eventAdapterConfiguration.getName(), message, "Job queue is full", e, log, tenantId);
        }
    }

    @Override
    public void disconnect() {
        //Nothing to do.
    }

    @Override
    public void destroy() {
        //Nothing to be destroyed.
    }

    @Override
    public boolean isPolled() {
        return true;
    }

    private class WebSocketSender implements Runnable {

        private String message;

        public WebSocketSender(String message) {
            this.message = message;
        }

        /**
         * When an object implementing interface <code>Runnable</code> is used
         * to create a thread, starting the thread causes the object's
         * <code>run</code> method to be called in that separately executing
         * thread.
         * <p/>
         * The general contract of the method <code>run</code> is that it may
         * take any action whatsoever.
         *
         * @see Thread#run()
         */
        @Override
        public void run() {

            WebsocketLocalOutputCallbackRegisterServiceImpl websocketLocalOutputCallbackRegisterServiceImpl = WebsocketLocalEventAdaptorServiceInternalValueHolder.getWebsocketLocalOutputCallbackRegisterServiceImpl();
            CopyOnWriteArrayList<Session> sessions = websocketLocalOutputCallbackRegisterServiceImpl.getSessions(tenantId, eventAdapterConfiguration.getName());
            if (sessions != null) {
                for (Session session : sessions) {
                    synchronized (session) {
                        try {
                            session.getBasicRemote().sendText(message);
                        } catch (IOException e) {
                            EventAdapterUtil.logAndDrop(eventAdapterConfiguration.getName(), message, "Cannot send to endpoint", e, log, tenantId);
                        }
                    }
                }
            } else {
                EventAdapterUtil.logAndDrop(eventAdapterConfiguration.getName(), message, "Cannot send as session not available", log, tenantId);
            }

        }
    }
}
