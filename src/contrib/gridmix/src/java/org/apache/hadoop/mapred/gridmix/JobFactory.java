/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.hadoop.mapred.gridmix;

import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.io.IOUtils;
import org.apache.hadoop.mapred.JobHistory;
import org.apache.hadoop.tools.rumen.JobStory;
import org.apache.hadoop.tools.rumen.JobStoryProducer;
import org.apache.hadoop.tools.rumen.ZombieJobProducer;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;


/**
 * Component reading job traces generated by Rumen. Each job in the trace is
 * assigned a sequence number and given a submission time relative to the
 * job that preceded it. Jobs are enqueued in the JobSubmitter provided at
 * construction.
 * @see org.apache.hadoop.tools.rumen.HadoopLogsAnalyzer
 */
class JobFactory implements Gridmix.Component<Void> {

  public static final Log LOG = LogFactory.getLog(JobFactory.class);

  private final Path scratch;
  private final Configuration conf;
  private final ReaderThread rThread;
  private final AtomicInteger sequence;
  private final JobSubmitter submitter;
  private final CountDownLatch startFlag;
  private volatile IOException error = null;
  protected final JobStoryProducer jobProducer;

  /**
   * Creating a new instance does not start the thread.
   * @param submitter Component to which deserialized jobs are passed
   * @param jobTrace Stream of job traces with which to construct a
   *                 {@link org.apache.hadoop.tools.rumen.ZombieJobProducer}
   * @param scratch Directory into which to write output from simulated jobs
   * @param conf Config passed to all jobs to be submitted
   * @param startFlag Latch released from main to start pipeline
   */
  public JobFactory(JobSubmitter submitter, InputStream jobTrace,
      Path scratch, Configuration conf, CountDownLatch startFlag)
      throws IOException {
    this(submitter, new ZombieJobProducer(jobTrace, null), scratch, conf,
        startFlag);
  }

  /**
   * Constructor permitting JobStoryProducer to be mocked.
   * @param submitter Component to which deserialized jobs are passed
   * @param jobProducer Producer generating JobStory objects.
   * @param scratch Directory into which to write output from simulated jobs
   * @param conf Config passed to all jobs to be submitted
   * @param startFlag Latch released from main to start pipeline
   */
  protected JobFactory(JobSubmitter submitter, JobStoryProducer jobProducer,
      Path scratch, Configuration conf, CountDownLatch startFlag) {
    sequence = new AtomicInteger(0);
    this.scratch = scratch;
    this.jobProducer = jobProducer;
    this.conf = new Configuration(conf);
    this.submitter = submitter;
    this.startFlag = startFlag;
    this.rThread = new ReaderThread();
  }

  /**
   * Worker thread responsible for reading descriptions, assigning sequence
   * numbers, and normalizing time.
   */
  private class ReaderThread extends Thread {

    public ReaderThread() {
      super("GridmixJobFactory");
    }

    private JobStory getNextJobFiltered() throws IOException {
      JobStory job;
      do {
        job = jobProducer.getNextJob();
      } while (job != null
          && (job.getOutcome() != JobHistory.Values.SUCCESS ||
              job.getSubmissionTime() < 0));
      return job;
    }

    @Override
    public void run() {
      try {
        startFlag.await();
        if (Thread.currentThread().isInterrupted()) {
          return;
        }
        final long initTime = TimeUnit.MILLISECONDS.convert(
            System.nanoTime(), TimeUnit.NANOSECONDS);
        LOG.debug("START @ " + initTime);
        long first = -1;
        long last = -1;
        while (!Thread.currentThread().isInterrupted()) {
          try {
            final JobStory job = getNextJobFiltered();
            if (null == job) {
              return;
            }
            if (first < 0) {
              first = job.getSubmissionTime();
            }
            final long current = job.getSubmissionTime();
            if (current < last) {
              throw new IOException(
                  "JobStories are not ordered by submission time.");
            }
            last = current;
            submitter.add(new GridmixJob(conf, initTime + (current - first),
                job, scratch, sequence.getAndIncrement()));
          } catch (IOException e) {
            JobFactory.this.error = e;
            return;
          }
        }
      } catch (InterruptedException e) {
        // exit thread; ignore any jobs remaining in the trace
        return;
      } finally {
        IOUtils.cleanup(null, jobProducer);
      }
    }
  }

  /**
   * Obtain the error that caused the thread to exit unexpectedly.
   */
  public IOException error() {
    return error;
  }

  /**
   * Add is disabled.
   * @throws UnsupportedOperationException
   */
  public void add(Void ignored) {
    throw new UnsupportedOperationException(getClass().getName() +
        " is at the start of the pipeline and accepts no events");
  }

  /**
   * Start the reader thread, wait for latch if necessary.
   */
  public void start() {
    rThread.start();
  }

  /**
   * Wait for the reader thread to exhaust the job trace.
   */
  public void join() throws InterruptedException {
    rThread.join();
  }

  /**
   * Interrupt the reader thread.
   */
  public void shutdown() {
    rThread.interrupt();
  }

  /**
   * Interrupt the reader thread. This requires no special consideration, as
   * the thread has no pending work queue.
   */
  public void abort() {
    // Currently no special work
    rThread.interrupt();
  }

}