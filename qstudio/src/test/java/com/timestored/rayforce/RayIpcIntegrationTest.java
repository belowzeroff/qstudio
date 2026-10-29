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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Assume;
import org.junit.Test;

import kx.c.Timespan;

/** Optional protocol checks against a real Rayforce process. */
public class RayIpcIntegrationTest {

	@Test public void decodesTimespansFromRayforce() throws Exception {
		String portText = System.getenv("RAYFORCE_TEST_PORT");
		Assume.assumeNotNull(portText);

		RayIpc ipc = new RayIpc("127.0.0.1", Integer.parseInt(portText), null, null, 5000);
		try {
			Object value = ipc.query("(list 0D00:00:01.000000000 0Nn (as 'TIMESPAN [0 -1 0Nl]))").getValue();
			assertTrue(value instanceof Object[]);
			Object[] result = (Object[]) value;
			assertEquals(new Timespan(1_000_000_000L), result[0]);
			assertEquals(new Timespan(Long.MIN_VALUE), result[1]);
			assertArrayEquals(new Timespan[] {
					new Timespan(0L), new Timespan(-1L), new Timespan(Long.MIN_VALUE)
			}, (Timespan[]) result[2]);
		} finally {
			ipc.close();
		}
	}
}
