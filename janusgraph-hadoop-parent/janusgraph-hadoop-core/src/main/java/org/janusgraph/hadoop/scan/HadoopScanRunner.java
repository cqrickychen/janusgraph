// Copyright 2017 JanusGraph Authors
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package org.janusgraph.hadoop.scan;

import com.google.common.base.Preconditions;
import org.janusgraph.diskstorage.configuration.*;
import org.janusgraph.diskstorage.configuration.Configuration;
import org.janusgraph.diskstorage.keycolumnvalue.scan.ScanJob;
import org.janusgraph.diskstorage.keycolumnvalue.scan.ScanMetrics;
import org.janusgraph.graphdb.olap.VertexScanJob;
import org.janusgraph.hadoop.compat.HadoopCompatLoader;
import org.janusgraph.hadoop.config.ModifiableHadoopConfiguration;
import org.janusgraph.hadoop.config.JanusGraphHadoopConfiguration;
import org.apache.hadoop.io.NullWritable;
import org.apache.hadoop.mapreduce.InputFormat;
import org.apache.hadoop.mapreduce.Job;
import org.apache.hadoop.mapreduce.Mapper;
import org.apache.hadoop.mapreduce.lib.output.NullOutputFormat;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;

import static org.janusgraph.hadoop.compat.HadoopCompatLoader.DEFAULT_COMPAT;

/**
 * Utility class to construct and submit Hadoop Jobs that execute a {@link HadoopScanMapper}.
 */
public class HadoopScanRunner {

    private static final Logger log =
            LoggerFactory.getLogger(HadoopScanRunner.class);

    /**
     * Run a ScanJob on Hadoop MapReduce.
     * <p>
     * The {@code confRootField} parameter must be a string in the format
     * {@code package.package...class#fieldname}, where {@code fieldname} is the
     * name of a public static field on the class specified by the portion of the
     * string before the {@code #}.  The {@code #} itself is just a separator and
     * is discarded.
     * <p>
     * When a MapReduce task process prepares to execute the {@code ScanJob}, it will
     * read the public static field named by {@code confFieldRoot} and cast it to a
     * {@link ConfigNamespace}.  This namespace object becomes the root of a
     * {@link Configuration} instantiated, populated with the key-value pairs
     * from the {@code conf} parameter, and then passed into the {@code ScanJob}.
     * <p>
     * This method blocks until the ScanJob completes, then returns the metrics
     * generated by the job during its execution.  It does not timeout.
     *
     * @param conf configuration settings for the ScanJob
     * @param confRootField the root of the ScanJob's configuration
     * @param hadoopConf the Configuration passed to the MapReduce Job
     * @param inputFormat the InputFormat<StaticBuffer, Iterable<Entry>>
     *        that reads (row, columns) pairs out of a JanusGraph edgestore
     * @return metrics generated by the ScanJob
     * @throws IOException if the job fails for any reason
     * @throws ClassNotFoundException if {@code scanJob.getClass()} or if Hadoop
     *         MapReduce's internal job-submission-related reflection fails
     * @throws InterruptedException if interrupted while waiting for the Hadoop
     *         MapReduce job to complete
     */
    public static ScanMetrics runJob(Configuration conf, String confRootField,
                                     org.apache.hadoop.conf.Configuration hadoopConf,
                                     Class<? extends InputFormat> inputFormat, String jobName,
                                     Class<? extends Mapper> mapperClass)
            throws IOException, InterruptedException, ClassNotFoundException {

        Preconditions.checkArgument(null != hadoopConf);
        Preconditions.checkArgument(null != inputFormat);

        if (null != conf) {
            Preconditions.checkArgument(null != confRootField,
                    "Configuration root field must be provided when configuration instance is provided");
        }

        ModifiableHadoopConfiguration scanConf =
                ModifiableHadoopConfiguration.of(JanusGraphHadoopConfiguration.MAPRED_NS, hadoopConf);

        if (null != confRootField) {
            // Set the scanjob configuration root
            scanConf.set(JanusGraphHadoopConfiguration.SCAN_JOB_CONFIG_ROOT, confRootField);

            // Instantiate scanjob configuration root
            ConfigNamespace confRoot = HadoopScanMapper.getJobRoot(confRootField);

            // Create writable view of scanjob configuration atop the Hadoop Configuration instance, where all keys are prefixed with SCAN_JOB_CONFIG_KEYS
            ModifiableConfiguration hadoopJobConf = ModifiableHadoopConfiguration.prefixView(confRoot,
                    JanusGraphHadoopConfiguration.SCAN_JOB_CONFIG_KEYS, scanConf);

            // Copy scanjob settings from the JanusGraph Configuration instance to the Hadoop Configuration instance
            Map<String, Object> jobConfMap = conf.getSubset(confRoot);
            for (Map.Entry<String, Object> jobConfEntry : jobConfMap.entrySet()) {
                hadoopJobConf.set((ConfigOption) ConfigElement.parse(confRoot, jobConfEntry.getKey()).element, jobConfEntry.getValue());
            }
        }

        return runJob(scanConf.getHadoopConfiguration(), inputFormat, jobName, mapperClass);
    }

    public static ScanMetrics runJob(org.apache.hadoop.conf.Configuration hadoopConf,
                                     Class<? extends InputFormat> inputFormat, String jobName,
                                     Class<? extends Mapper> mapperClass)
            throws IOException, InterruptedException, ClassNotFoundException {

        Job job = Job.getInstance(hadoopConf);

        //job.setJarByClass(HadoopScanMapper.class);
        job.setJarByClass(mapperClass);
        //job.setJobName(HadoopScanMapper.class.getSimpleName() + "[" + scanJob + "]");
        job.setJobName(jobName);
        job.setOutputKeyClass(NullWritable.class);
        job.setOutputValueClass(NullWritable.class);
        job.setMapOutputKeyClass(NullWritable.class);
        job.setMapOutputValueClass(NullWritable.class);
        job.setNumReduceTasks(0);
        //job.setMapperClass(HadoopScanMapper.class);
        job.setMapperClass(mapperClass);
        job.setOutputFormatClass(NullOutputFormat.class);
        job.setInputFormatClass(inputFormat);

        boolean success = job.waitForCompletion(true);

        if (!success) {
            String f;
            try {
                // Just in case one of Job's methods throws an exception
                f = String.format("MapReduce JobID %s terminated abnormally: %s",
                        job.getJobID().toString(), HadoopCompatLoader.DEFAULT_COMPAT.getJobFailureString(job));
            } catch (RuntimeException e) {
                f = "Job failed (unable to read job status programmatically -- see MapReduce logs for information)";
            }
            throw new IOException(f);
        } else {
            return DEFAULT_COMPAT.getMetrics(job.getCounters());
        }
    }

    public static ScanMetrics runScanJob(ScanJob scanJob, Configuration conf, String confRootField,
                                     org.apache.hadoop.conf.Configuration hadoopConf,
                                     Class<? extends InputFormat> inputFormat)
            throws IOException, InterruptedException, ClassNotFoundException {

        ModifiableHadoopConfiguration scanConf =
                ModifiableHadoopConfiguration.of(JanusGraphHadoopConfiguration.MAPRED_NS, hadoopConf);

        tryToLoadClassByName(scanJob);

        // Set the ScanJob class
        scanConf.set(JanusGraphHadoopConfiguration.SCAN_JOB_CLASS, scanJob.getClass().getName());

        String jobName = HadoopScanMapper.class.getSimpleName() + "[" + scanJob + "]";

        return runJob(conf, confRootField, hadoopConf, inputFormat, jobName, HadoopScanMapper.class);
    }

    public static ScanMetrics runVertexScanJob(VertexScanJob vertexScanJob, Configuration conf, String confRootField,
                                         org.apache.hadoop.conf.Configuration hadoopConf,
                                         Class<? extends InputFormat> inputFormat)
            throws IOException, InterruptedException, ClassNotFoundException {

        ModifiableHadoopConfiguration scanConf =
                ModifiableHadoopConfiguration.of(JanusGraphHadoopConfiguration.MAPRED_NS, hadoopConf);

        tryToLoadClassByName(vertexScanJob);

        // Set the VertexScanJob class
        scanConf.set(JanusGraphHadoopConfiguration.SCAN_JOB_CLASS, vertexScanJob.getClass().getName());

        String jobName = HadoopScanMapper.class.getSimpleName() + "[" + vertexScanJob + "]";

        return runJob(conf, confRootField, hadoopConf, inputFormat, jobName, HadoopVertexScanMapper.class);
    }

    private static void tryToLoadClassByName(Object o) throws ClassNotFoundException {
        // Test that we can find this ScanJob class by its name; better to detect a problem here than in the mappers
        String scanJobClassname = o.getClass().getName();
        try {
            Class.forName(scanJobClassname);
        } catch (ClassNotFoundException e) {
            log.error("Unable to locate class with name {}", scanJobClassname, e);
            throw e;
        }
    }


}
