/*
 * qStudio - Free SQL Analysis Tool
 * Copyright C 2013-2023 TimeStored
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
package com.timestored.rayforce;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.After;
import org.junit.Test;

/** Connection-level tests for reconnect behaviour. */
public class RayConnectionTest {

	private DropAfterRequestServer server;

	@After public void tearDown() throws IOException {
		if (server != null) {
			server.close();
		}
	}

	@Test public void doesNotReplayAQueryWhenTheReplyIsLost() throws Exception {
		server = new DropAfterRequestServer();
		RayConnection connection = new RayConnection("127.0.0.1", server.port(), null, null);
		try {
			connection.query("(insert 'trades [1])");
			fail("expected the dropped reply to fail the query");
		} catch (IOException e) {
			assertTrue(e.getMessage(), e.getMessage().contains("may have completed"));
			assertTrue(e.getMessage(), e.getMessage().contains("was not retried"));
		}

		assertEquals("the mutating query must be sent exactly once", 1, server.requests.get());
		assertEquals("a fresh connection should be ready for the next query", 2, server.connections.get());
		assertTrue(connection.isConnected());
		assertEquals(Long.valueOf(42), connection.query("42"));
		assertEquals("only the explicit next query should use the new connection", 2, server.requests.get());
		connection.close();
	}

	/** Drops the first connection after reading its request, then accepts a reconnect. */
	private static final class DropAfterRequestServer implements Runnable {
		private static final byte WIRE_VERSION = 3;
		private static final int PREFIX = 0xcefadefa;

		private final ServerSocket serverSocket;
		private final AtomicInteger connections = new AtomicInteger();
		private final AtomicInteger requests = new AtomicInteger();
		private volatile Socket reconnectedSocket;

		DropAfterRequestServer() throws IOException {
			serverSocket = new ServerSocket(0);
			Thread thread = new Thread(this, "drop-after-rayforce-request");
			thread.setDaemon(true);
			thread.start();
		}

		int port() { return serverSocket.getLocalPort(); }

		@Override public void run() {
			try (Socket first = serverSocket.accept()) {
				handshake(first);
				readRequest(first);
				requests.incrementAndGet();
			} catch (IOException expectedOnDrop) {
				return;
			}

			try {
				reconnectedSocket = serverSocket.accept();
				handshake(reconnectedSocket);
				readRequest(reconnectedSocket);
				requests.incrementAndGet();
				writeI64Response(reconnectedSocket, 42);
			} catch (IOException expectedOnClose) {
				// the test finished
			}
		}

		private void handshake(Socket socket) throws IOException {
			DataInputStream in = new DataInputStream(socket.getInputStream());
			OutputStream out = socket.getOutputStream();
			in.readByte();
			in.readByte();
			out.write(new byte[] { WIRE_VERSION, 0 });
			out.flush();
			connections.incrementAndGet();
		}

		private void readRequest(Socket socket) throws IOException {
			DataInputStream in = new DataInputStream(socket.getInputStream());
			byte[] header = new byte[16];
			in.readFully(header);
			long size = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getLong(8);
			byte[] body = new byte[(int) size];
			in.readFully(body);
		}

		private void writeI64Response(Socket socket, long value) throws IOException {
			ByteBuffer frame = ByteBuffer.allocate(16 + 10).order(ByteOrder.LITTLE_ENDIAN);
			frame.putInt(PREFIX);
			frame.put(WIRE_VERSION);
			frame.put((byte) 0); // flags
			frame.put((byte) 0); // little endian
			frame.put((byte) 2); // response
			frame.putLong(10);
			frame.put((byte) -5); // i64 atom
			frame.put((byte) 0); // atom flags
			frame.putLong(value);
			OutputStream out = socket.getOutputStream();
			out.write(frame.array());
			out.flush();
		}

		void close() throws IOException {
			if (reconnectedSocket != null) {
				reconnectedSocket.close();
			}
			serverSocket.close();
		}
	}
}
