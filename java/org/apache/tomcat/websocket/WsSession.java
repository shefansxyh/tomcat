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
package org.apache.tomcat.websocket;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.security.Principal;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.DeploymentException;
import javax.websocket.Encoder;
import javax.websocket.Endpoint;
import javax.websocket.Extension;
import javax.websocket.MessageHandler;
import javax.websocket.PongMessage;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;
import javax.websocket.WebSocketContainer;

import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.res.StringManager;

public class WsSession implements Session {

    private static final Charset UTF8 = Charset.forName("UTF8");
    private static final StringManager sm =
            StringManager.getManager(Constants.PACKAGE_NAME);
    private static AtomicLong ids = new AtomicLong(0);

    private final Log log = LogFactory.getLog(WsSession.class);

    private final Endpoint localEndpoint;
    private final WsRemoteEndpointImplBase wsRemoteEndpoint;
    private final RemoteEndpoint.Async remoteEndpointAsync;
    private final RemoteEndpoint.Basic remoteEndpointBasic;
    private final ClassLoader applicationClassLoader;
    private final WsWebSocketContainer webSocketContainer;
    private final WsRequest request;
    private final String subProtocol;
    private final Map<String,String> pathParameters;
    private final boolean secure;
    private final String id;

    private MessageHandler textMessageHandler = null;
    private MessageHandler binaryMessageHandler = null;
    private MessageHandler.Whole<PongMessage> pongMessageHandler = null;
    private volatile State state = State.OPEN;
    private final Object stateLock = new Object();
    private final Map<String,Object> userProperties = new ConcurrentHashMap<>();
    private volatile int maxBinaryMessageBufferSize =
            Constants.DEFAULT_BUFFER_SIZE;
    private volatile int maxTextMessageBufferSize =
            Constants.DEFAULT_BUFFER_SIZE;
    private volatile long maxIdleTimeout = 0;
    private volatile long lastActive = System.currentTimeMillis();

    /**
     * Creates a new WebSocket session for communication between the two
     * provided end points. The result of {@link Thread#getContextClassLoader()}
     * at the time this constructor is called will be used when calling
     * {@link Endpoint#onClose(Session, CloseReason)}.
     *
     * @param localEndpoint
     * @param wsRemoteEndpoint
     * @throws DeploymentException
     */
    public WsSession(Endpoint localEndpoint,
            WsRemoteEndpointImplBase wsRemoteEndpoint,
            WsWebSocketContainer wsWebSocketContainer,
            WsRequest request, String subProtocol,
            Map<String,String> pathParameters,
            boolean secure, List<Class<? extends Encoder>> encoders)
                    throws DeploymentException {
        this.localEndpoint = localEndpoint;
        this.wsRemoteEndpoint = wsRemoteEndpoint;
        this.wsRemoteEndpoint.setSession(this);
        this.remoteEndpointAsync = new WsRemoteEndpointAsync(wsRemoteEndpoint);
        this.remoteEndpointBasic = new WsRemoteEndpointBasic(wsRemoteEndpoint);
        this.webSocketContainer = wsWebSocketContainer;
        applicationClassLoader = Thread.currentThread().getContextClassLoader();
        wsRemoteEndpoint.setSendTimeout(
                wsWebSocketContainer.getDefaultAsyncSendTimeout());
        this.maxBinaryMessageBufferSize =
                webSocketContainer.getDefaultMaxBinaryMessageBufferSize();
        this.maxTextMessageBufferSize =
                webSocketContainer.getDefaultMaxTextMessageBufferSize();
        this.maxIdleTimeout =
                webSocketContainer.getDefaultMaxSessionIdleTimeout();
        this.request = request;
        this.subProtocol = subProtocol;
        this.pathParameters = pathParameters;
        this.secure = secure;
        this.wsRemoteEndpoint.setEncoders(encoders);

        this.id = Long.toHexString(ids.getAndIncrement());
    }


    @Override
    public WebSocketContainer getContainer() {
        return webSocketContainer;
    }


    @SuppressWarnings("unchecked")
    @Override
    public void addMessageHandler(MessageHandler listener) {
        Type t = Util.getMessageType(listener);

        if (t.equals(String.class)) {
            if (textMessageHandler != null) {
                throw new IllegalStateException(
                        sm.getString("wsSession.duplicateHandlerText"));
            }
            textMessageHandler = listener;
        } else if (t.equals(ByteBuffer.class)) {
            if (binaryMessageHandler != null) {
                throw new IllegalStateException(
                        sm.getString("wsSession.duplicateHandlerBinary"));
            }
            binaryMessageHandler = listener;
        } else if (t.equals(PongMessage.class)) {
            if (pongMessageHandler != null) {
                throw new IllegalStateException(
                        sm.getString("wsSession.duplicateHandlerPong"));
            }
            if (listener instanceof MessageHandler.Whole<?>) {
                pongMessageHandler =
                        (MessageHandler.Whole<PongMessage>) listener;
            } else {
                throw new IllegalStateException(
                        sm.getString("wsSession.invalidHandlerTypePong"));
            }
        } else {
            throw new IllegalArgumentException(
                    sm.getString("wsSession.unknownHandler", listener, t));
        }
    }


    @Override
    public Set<MessageHandler> getMessageHandlers() {
        Set<MessageHandler> result = new HashSet<>();
        if (binaryMessageHandler != null) {
            result.add(binaryMessageHandler);
        }
        if (textMessageHandler != null) {
            result.add(textMessageHandler);
        }
        if (pongMessageHandler != null) {
            result.add(pongMessageHandler);
        }
        return result;
    }


    @Override
    public void removeMessageHandler(MessageHandler listener) {
        if (listener == null) {
            return;
        }
        if (listener.equals(textMessageHandler)) {
            textMessageHandler = null;
        } else if (listener.equals(binaryMessageHandler)) {
            binaryMessageHandler = null;
        } else if (listener.equals(pongMessageHandler)) {
            pongMessageHandler = null;
        }

        // ISE for now. Could swallow this silently / log this if the ISE
        // becomes a problem
        throw new IllegalStateException(
                sm.getString("wsSession.removeHandlerFailed", listener));
    }


    @Override
    public String getProtocolVersion() {
        return Constants.WS_VERSION_HEADER_VALUE;
    }


    @Override
    public String getNegotiatedSubprotocol() {
        return subProtocol;
    }


    @Override
    public List<Extension> getNegotiatedExtensions() {
        return Collections.EMPTY_LIST;
    }


    @Override
    public boolean isSecure() {
        return secure;
    }


    @Override
    public boolean isOpen() {
        return state == State.OPEN;
    }


    @Override
    public long getMaxIdleTimeout() {
        return maxIdleTimeout;
    }


    @Override
    public void setMaxIdleTimeout(long timeout) {
        this.maxIdleTimeout = timeout;
    }


    @Override
    public void setMaxBinaryMessageBufferSize(int max) {
        this.maxBinaryMessageBufferSize = max;
    }


    @Override
    public int getMaxBinaryMessageBufferSize() {
        return maxBinaryMessageBufferSize;
    }


    @Override
    public void setMaxTextMessageBufferSize(int max) {
        this.maxTextMessageBufferSize = max;
    }


    @Override
    public int getMaxTextMessageBufferSize() {
        return maxTextMessageBufferSize;
    }


    @Override
    public Set<Session> getOpenSessions() {
        return webSocketContainer.getOpenSessions(localEndpoint.getClass());
    }


    @Override
    public RemoteEndpoint.Async getAsyncRemote() {
        return remoteEndpointAsync;
    }


    @Override
    public RemoteEndpoint.Basic getBasicRemote() {
        return remoteEndpointBasic;
    }


    @Override
    public void close() throws IOException {
        close(new CloseReason(CloseCodes.NORMAL_CLOSURE, ""));
    }


    @Override
    public void close(CloseReason closeReason) throws IOException {
        // Double-checked locking. OK because state is volatile
        if (state != State.OPEN) {
            return;
        }
        synchronized (stateLock) {
            if (state != State.OPEN) {
                return;
            }
            state = State.CLOSING;

            sendCloseMessage(closeReason);
        }
    }


    /**
     * Called when a close message is received. Should only ever happen once.
     */
    void onClose(CloseReason closeReason) {

        boolean sendCloseMessage = false;

        synchronized (stateLock) {
            if (state == State.OPEN) {
                sendCloseMessage = true;
            }

            state = State.CLOSED;

            if (sendCloseMessage) {
                sendCloseMessage(closeReason);
            }

            // Close the socket
            wsRemoteEndpoint.close();
        }
    }


    private void sendCloseMessage(CloseReason closeReason) {
        // 125 is maximum size for the payload of a control message
        ByteBuffer msg = ByteBuffer.allocate(125);
        msg.putShort((short) closeReason.getCloseCode().getCode());
        String reason = closeReason.getReasonPhrase();
        if (reason != null && reason.length() > 0) {
            msg.put(reason.getBytes(UTF8));
        }
        msg.flip();
        try {
            wsRemoteEndpoint.startMessageBlock(
                    Constants.OPCODE_CLOSE, msg, true);
        } catch (IOException ioe) {
            // Failed to send close message. Close the socket and let the caller
            // deal with the Exception
            log.error(sm.getString("wsSession.sendCloseFail"), ioe);
            wsRemoteEndpoint.close();
            localEndpoint.onError(this, ioe);
        } finally {
            webSocketContainer.unregisterSession(
                    localEndpoint.getClass(), this);

            // Fire the onClose event
            Thread t = Thread.currentThread();
            ClassLoader cl = t.getContextClassLoader();
            t.setContextClassLoader(applicationClassLoader);
            try {
                localEndpoint.onClose(this, closeReason);
            } finally {
                t.setContextClassLoader(cl);
            }
        }

    }


    @Override
    public URI getRequestURI() {
        if (request == null) {
            return null;
        }
        return request.getRequestURI();
    }


    @Override
    public Map<String,List<String>> getRequestParameterMap() {
        if (request == null) {
            return Collections.EMPTY_MAP;
        }
        return request.getRequestParameterMap();
    }


    @Override
    public String getQueryString() {
        if (request == null) {
            return null;
        }
        return request.getQueryString();
    }


    @Override
    public Principal getUserPrincipal() {
        if (request == null) {
            return null;
        }
        return request.getUserPrincipal();
    }


    @Override
    public Map<String,String> getPathParameters() {
        return pathParameters;
    }


    @Override
    public String getId() {
        return id;
    }


    @Override
    public Map<String,Object> getUserProperties() {
        return userProperties;
    }


    public Endpoint getLocal() {
        return localEndpoint;
    }


    protected MessageHandler getTextMessageHandler() {
        return textMessageHandler;
    }


    protected MessageHandler getBinaryMessageHandler() {
        return binaryMessageHandler;
    }


    protected MessageHandler.Whole<PongMessage> getPongMessageHandler() {
        return pongMessageHandler;
    }


    protected void updateLastActive() {
        lastActive = System.currentTimeMillis();
    }


    protected void expire() {
        long timeout = maxIdleTimeout;
        if (timeout < 1) {
            return;
        }

        if (System.currentTimeMillis() - lastActive > timeout) {
            try {
                close(new CloseReason(CloseCodes.GOING_AWAY,
                        sm.getString("wsSession.timeout")));
            } catch (IOException e) {
                log.warn(sm.getString("wsSession.expireFailed"), e);
            }
        }
    }


    private static enum State {
        OPEN,
        CLOSING,
        CLOSED
    }
}
