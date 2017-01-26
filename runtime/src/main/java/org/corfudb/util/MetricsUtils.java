package org.corfudb.util;

import com.codahale.metrics.CsvReporter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.MetricSet;
import com.codahale.metrics.jvm.FileDescriptorRatioGauge;
import com.codahale.metrics.jvm.GarbageCollectorMetricSet;
import com.codahale.metrics.jvm.MemoryUsageGaugeSet;
import com.codahale.metrics.jvm.ThreadStatesGaugeSet;
import com.github.benmanes.caffeine.cache.Cache;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import java.io.File;
import java.io.FileInputStream;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Slf4j
public class MetricsUtils {
    private static final FileDescriptorRatioGauge metricsJVMFdGauge = new FileDescriptorRatioGauge();
    private static final MetricSet metricsJVMGC = new GarbageCollectorMetricSet();
    private static final MetricSet metricsJVMMem = new MemoryUsageGaugeSet();
    private static final MetricSet metricsJVMThread = new ThreadStatesGaugeSet();

    private static Properties metricsProperties = new Properties();
    private static boolean metricsReportingEnabled = false;
    private static String mpTrigger = "filter-trigger"; // internal use only

    /**
     * Load a metrics properties file.
     * The expected properties in this properties file are:
     * <p>
     * enabled: Boolean for whether CSV output will be generated.
     * For each reporting interval, this function will be
     * called to re-parse the properties file and to
     * re-evaluate the value of 'enabled'.  Changes to
     * any other property in this file will be ignored.
     * <p>
     * directory: String for the path to the CSV output subdirectory
     * <p>
     * interval: Long for the reporting interval for CSV output
     */
    private static void loadPropertiesFile() {
        String propPath;

        if ((propPath = System.getenv("METRICS_PROPERTIES")) != null) {
            try {
                metricsProperties.load(new FileInputStream(propPath));
                metricsReportingEnabled = Boolean.valueOf((String) metricsProperties.get("enabled"));
            } catch (Exception e) {
                log.error("Error processing METRICS_PROPERTIES {}: {}", propPath, e.toString());
            }
        }
    }

    /**
     * Start metrics reporting via the Dropwizard 'CsvReporter' file writer.
     * Reporting can be turned on and off via the properties file described
     * in loadPropertiesFile()'s docs.  The report interval and report
     * directory cannot be altered at runtime.
     *
     * @param metrics
     */
    public static void metricsReportingSetup(MetricRegistry metrics) {
        metrics.counter(mpTrigger);
        loadPropertiesFile();
        String outPath = (String) metricsProperties.get("directory");
        if (outPath != null && !outPath.isEmpty()) {
            Long interval = Long.valueOf((String) metricsProperties.get("interval"));
            File statDir = new File(outPath);
            statDir.mkdirs();
            MetricFilter f = (name, metric) -> {
                if (name.equals(mpTrigger)) {
                    loadPropertiesFile();
                    return false;
                }
                return metricsReportingEnabled;
            };
            CsvReporter reporter1 = CsvReporter.forRegistry(metrics)
                    .formatFor(Locale.US)
                    .convertRatesTo(TimeUnit.SECONDS)
                    .convertDurationsTo(TimeUnit.MILLISECONDS)
                    .filter(f)
                    .build(statDir);
            reporter1.start(interval, TimeUnit.SECONDS);
        }
    }

    public static void addCacheGauges(MetricRegistry metrics, String name, Cache cache) {
        try {
            metrics.register(name + "cache-size", (Gauge<Long>) () -> cache.estimatedSize());
            metrics.register(name + "evictions", (Gauge<Long>) () -> cache.stats().evictionCount());
            metrics.register(name + "hit-rate", (Gauge<Double>) () -> cache.stats().hitRate());
            metrics.register(name + "hits", (Gauge<Long>) () -> cache.stats().hitCount());
            metrics.register(name + "misses", (Gauge<Long>) () -> cache.stats().missCount());
        } catch (IllegalArgumentException e) {
            // Re-registering metrics during test runs, not a problem
        }
    }

    public static void addJVMMetrics(MetricRegistry metrics, String pfx) {
        try {
            metrics.register(pfx + "jvm.gc", metricsJVMGC);
            metrics.register(pfx + "jvm.memory", metricsJVMMem);
            metrics.register(pfx + "jvm.thread", metricsJVMThread);
            metrics.register(pfx + "jvm.file-descriptors-used", metricsJVMFdGauge);
        } catch (IllegalArgumentException e) {
            // Re-registering metrics during test runs, not a problem
        }
    }
}
