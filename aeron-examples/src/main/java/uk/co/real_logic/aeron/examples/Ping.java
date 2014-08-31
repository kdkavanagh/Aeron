/*
 * Copyright 2014 Real Logic Ltd.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.co.real_logic.aeron.examples;

import org.HdrHistogram.Histogram;
import uk.co.real_logic.aeron.Aeron;
import uk.co.real_logic.aeron.Publication;
import uk.co.real_logic.aeron.Subscription;
import uk.co.real_logic.aeron.common.BusySpinIdleStrategy;
import uk.co.real_logic.aeron.common.CloseHelper;
import uk.co.real_logic.aeron.common.IdleStrategy;
import uk.co.real_logic.aeron.common.concurrent.AtomicBuffer;
import uk.co.real_logic.aeron.driver.MediaDriver;

import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

/**
 * Ping component of Ping-Pong.
 *
 * Initiates and records times.
 */
public class Ping
{
    private static final int PING_STREAM_ID = ExampleConfiguration.PING_STREAM_ID;
    private static final int PONG_STREAM_ID = ExampleConfiguration.PONG_STREAM_ID;
    private static final String PING_CHANNEL = ExampleConfiguration.PING_CHANNEL;
    private static final String PONG_CHANNEL = ExampleConfiguration.PONG_CHANNEL;
    private static final int NUMBER_OF_MESSAGES = ExampleConfiguration.NUMBER_OF_MESSAGES;
    private static final int WARMUP_NUMBER_OF_MESSAGES = ExampleConfiguration.WARMUP_NUMBER_OF_MESSAGES;
    private static final int WARMUP_NUMBER_OF_ITERATIONS = ExampleConfiguration.WARMUP_NUMBER_OF_ITERATIONS;
    private static final int MESSAGE_LENGTH = ExampleConfiguration.MESSAGE_LENGTH;
    private static final int FRAGMENT_COUNT_LIMIT = ExampleConfiguration.FRAGMENT_COUNT_LIMIT;
    private static final long LINGER_TIMEOUT_MS = ExampleConfiguration.LINGER_TIMEOUT_MS;
    private static final boolean EMBEDDED_MEDIA_DRIVER = ExampleConfiguration.EMBEDDED_MEDIA_DRIVER;

    private static final AtomicBuffer ATOMIC_BUFFER = new AtomicBuffer(ByteBuffer.allocateDirect(MESSAGE_LENGTH));

    private static Histogram histogram = new Histogram(TimeUnit.MILLISECONDS.toNanos(500), 3);

    public static void main(final String[] args) throws Exception
    {
        final MediaDriver driver = EMBEDDED_MEDIA_DRIVER ? MediaDriver.launch() : null;
        final ExecutorService executor = Executors.newSingleThreadExecutor();
        final Aeron.Context ctx = new Aeron.Context();

        System.out.println("Publishing Ping at " + PING_CHANNEL + " on stream Id " + PING_STREAM_ID);
        System.out.println("Subscribing Pong at " + PONG_CHANNEL + " on stream Id " + PONG_STREAM_ID);

        try (final Aeron aeron = Aeron.connect(ctx);
             final Publication pingPublication = aeron.addPublication(PING_CHANNEL, PING_STREAM_ID);
             final Subscription pongSubscription = aeron.addSubscription(PONG_CHANNEL, PONG_STREAM_ID, Ping::pongHandler))
        {
            final int totalWarmupMessages = WARMUP_NUMBER_OF_MESSAGES * WARMUP_NUMBER_OF_ITERATIONS;
            final Future warmup = executor.submit(() -> runSubscriber(pongSubscription, totalWarmupMessages));

            System.out.println(
                "Warming up... " + WARMUP_NUMBER_OF_ITERATIONS + " iterations of " + WARMUP_NUMBER_OF_MESSAGES + " messages");

            for (int i = 0; i < WARMUP_NUMBER_OF_ITERATIONS; i++)
            {
                sendMessages(pingPublication, WARMUP_NUMBER_OF_MESSAGES);
            }

            if (0 < LINGER_TIMEOUT_MS)
            {
                System.out.println("Lingering for " + LINGER_TIMEOUT_MS + " milliseconds...");
                Thread.sleep(LINGER_TIMEOUT_MS);
            }

            warmup.get();
            histogram.reset();

            System.out.println("Pinging " + NUMBER_OF_MESSAGES + " messages");

            final Future timedRun = executor.submit(() -> runSubscriber(pongSubscription, NUMBER_OF_MESSAGES));
            sendMessages(pingPublication, NUMBER_OF_MESSAGES);

            System.out.println("Done streaming.");

            if (0 < LINGER_TIMEOUT_MS)
            {
                System.out.println("Lingering for " + LINGER_TIMEOUT_MS + " milliseconds...");
                Thread.sleep(LINGER_TIMEOUT_MS);
            }

            timedRun.get();
        }

        System.out.println("Done playing... Histogram of RTT latencies in microseconds.");

        histogram.outputPercentileDistribution(System.out, 1000.0);

        executor.shutdown();
        CloseHelper.quietClose(driver);
    }

    private static void sendMessages(final Publication pingPublication, final int numMessages)
    {
        for (int i = 0; i < numMessages; i++)
        {
            do
            {
                ATOMIC_BUFFER.putLong(0, System.nanoTime());
            }
            while (!pingPublication.offer(ATOMIC_BUFFER, 0, MESSAGE_LENGTH));

            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
    }

    private static void pongHandler(
        final AtomicBuffer buffer, final int offset, final int length, final int sessionId, final byte flags)
    {
        final long pingTimestamp = buffer.getLong(offset);
        final long rttNs = System.nanoTime() - pingTimestamp;

        histogram.recordValue(rttNs);
    }

    private static void runSubscriber(final Subscription pongSubscription, final int numMessages)
    {
        final IdleStrategy idleStrategy = new BusySpinIdleStrategy();

        int totalFragments = 0;
        do
        {
            final int fragmentsRead = pongSubscription.poll(FRAGMENT_COUNT_LIMIT);
            totalFragments += fragmentsRead;
            idleStrategy.idle(fragmentsRead);
        }
        while (totalFragments < numMessages);
    }
}