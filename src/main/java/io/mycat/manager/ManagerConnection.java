/*
 * Copyright (c) 2013, OpenCloudDB/MyCAT and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software;Designed and Developed mainly by many Chinese
 * opensource volunteers. you can redistribute it and/or modify it under the
 * terms of the GNU General Public License version 2 only, as published by the
 * Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Any questions about this component can be directed to it's project Web address
 * https://code.google.com/p/opencloudb/.
 *
 */
package io.mycat.manager;

import java.beans.IntrospectionException;
import java.io.IOException;
import java.nio.channels.NetworkChannel;

import io.mycat.net.FrontendConnection;
import io.mycat.util.TimeUtil;
import org.apache.zookeeper.KeeperException;

/**
 * @author mycat
 */
public class ManagerConnection extends FrontendConnection {
	private static final long AUTH_TIMEOUT = 15 * 1000L;

	public ManagerConnection(NetworkChannel channel) throws IOException {
		super(channel);
	}

	@Override
	public boolean isIdleTimeout() {
		if (isAuthenticated) {
			return super.isIdleTimeout();
		} else {
			return TimeUtil.currentTimeMillis() > Math.max(lastWriteTime,
					lastReadTime) + AUTH_TIMEOUT;
		}
	}

	@Override
	public void handle(final byte[] data) throws IntrospectionException, InterruptedException, KeeperException {
		handler.handle(data);
	}

}