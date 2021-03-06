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
package org.apache.tomcat.websocket.pojo;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;

import javax.websocket.EncodeException;
import javax.websocket.RemoteEndpoint;
import javax.websocket.Session;

/**
 * Common implementation code for the POJO message handlers.
 *
 * @param <T>   The type of message to handle
 */
public abstract class PojoMessageHandlerBase<T> {

    protected final Object pojo;
    protected final Method method;
    protected final Session session;
    protected final Object[] params;
    protected final int indexPayload;
    protected final boolean unwrap;
    protected final int indexSession;


    public PojoMessageHandlerBase(Object pojo, Method method,
            Session session, Object[] params, int indexPayload, boolean unwrap,
            int indexSession) {
        this.pojo = pojo;
        this.method = method;
        this.session = session;
        this.params = params;
        this.indexPayload = indexPayload;
        this.unwrap = unwrap;
        this.indexSession = indexSession;
    }


    protected final void processResult(Object result) {
        if (result == null) {
            return;
        }

        RemoteEndpoint.Basic remoteEndpoint = session.getBasicRemote();
        try {
            if (result instanceof String) {
                remoteEndpoint.sendText((String) result);
            } else if (result instanceof ByteBuffer) {
                remoteEndpoint.sendBinary((ByteBuffer) result);
            } else if (result instanceof byte[]) {
                remoteEndpoint.sendBinary(ByteBuffer.wrap((byte[]) result));
            } else {
                remoteEndpoint.sendObject(result);
            }
        } catch (IOException | EncodeException ioe) {
            throw new IllegalStateException(ioe);
        }
    }
}
