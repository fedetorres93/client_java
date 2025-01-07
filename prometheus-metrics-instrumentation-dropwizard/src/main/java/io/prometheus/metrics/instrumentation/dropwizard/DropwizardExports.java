package io.prometheus.metrics.instrumentation.dropwizard;

import com.codahale.metrics.Counter;
import com.codahale.metrics.Gauge;
import com.codahale.metrics.Histogram;
import com.codahale.metrics.Meter;
import com.codahale.metrics.Metric;
import com.codahale.metrics.MetricFilter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Snapshot;
import com.codahale.metrics.Timer;
import io.prometheus.metrics.instrumentation.dropwizard5.labels.CustomLabelMapper;
import io.prometheus.metrics.model.registry.MultiCollector;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricMetadata;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import io.prometheus.metrics.model.snapshots.PrometheusNaming;
import io.prometheus.metrics.model.snapshots.Quantiles;
import io.prometheus.metrics.model.snapshots.SummarySnapshot;
import java.util.Collections;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/** Collect Dropwizard metrics from a MetricRegistry. */
public class DropwizardExports implements MultiCollector {
  private static final Logger logger = Logger.getLogger(DropwizardExports.class.getName());
  private final MetricRegistry registry;
  private final MetricFilter metricFilter;
  private final Optional<CustomLabelMapper> labelMapper;

  /**
   * Creates a new DropwizardExports and {@link MetricFilter#ALL}.
   *
   * @param registry a metric registry to export in prometheus.
   */
  public DropwizardExports(MetricRegistry registry) {
    super();
    this.registry = registry;
    this.metricFilter = MetricFilter.ALL;
    this.labelMapper = Optional.empty();
  }

  /**
   * Creates a new DropwizardExports with a custom {@link MetricFilter}.
   *
   * @param registry a metric registry to export in prometheus.
   * @param metricFilter a custom metric filter.
   */
  public DropwizardExports(MetricRegistry registry, MetricFilter metricFilter) {
    this.registry = registry;
    this.metricFilter = metricFilter;
    this.labelMapper = Optional.empty();
  }

  /**
   * @param registry a metric registry to export in prometheus.
   * @param metricFilter a custom metric filter.
   * @param labelMapper a labelMapper to use to map labels.
   */
  public DropwizardExports(
      MetricRegistry registry, MetricFilter metricFilter, CustomLabelMapper labelMapper) {
    this.registry = registry;
    this.metricFilter = metricFilter;
    this.labelMapper = Optional.ofNullable(labelMapper);
  }

  private static String getHelpMessage(String metricName, Metric metric) {
    return String.format(
        "Generated from Dropwizard metric import (metric=%s, type=%s)",
        metricName, metric.getClass().getName());
  }

  private MetricMetadata getMetricMetaData(String metricName, Metric metric) {
    String name = labelMapper.isPresent() ? labelMapper.get().getName(metricName) : metricName;
    return new MetricMetadata(
        PrometheusNaming.sanitizeMetricName(name), getHelpMessage(metricName, metric));
  }

  /**
   * Export counter as Prometheus <a
   * href="https://prometheus.io/docs/concepts/metric_types/#gauge">Gauge</a>.
   */
  MetricSnapshot fromCounter(String dropwizardName, Counter counter) {
    MetricMetadata metadata = getMetricMetaData(dropwizardName, counter);
    CounterSnapshot.CounterDataPointSnapshot.Builder dataPointBuilder =
        CounterSnapshot.CounterDataPointSnapshot.builder()
            .value(Long.valueOf(counter.getCount()).doubleValue());
    labelMapper.ifPresent(
        mapper ->
            dataPointBuilder.labels(
                mapper.getLabels(
                    dropwizardName, Collections.emptyList(), Collections.emptyList())));
    return new CounterSnapshot(metadata, Collections.singletonList(dataPointBuilder.build()));
  }

  /** Export gauge as a prometheus gauge. */
  MetricSnapshot fromGauge(String dropwizardName, Gauge<?> gauge) {
    Object obj = gauge.getValue();
    double value;
    if (obj instanceof Number) {
      value = ((Number) obj).doubleValue();
    } else if (obj instanceof Boolean) {
      value = ((Boolean) obj) ? 1 : 0;
    } else {
      logger.log(
          Level.FINE,
          String.format(
              "Invalid type for Gauge %s: %s",
              PrometheusNaming.sanitizeMetricName(dropwizardName),
              obj == null ? "null" : obj.getClass().getName()));
      return null;
    }
    MetricMetadata metadata = getMetricMetaData(dropwizardName, gauge);
    GaugeSnapshot.GaugeDataPointSnapshot.Builder dataPointBuilder =
        GaugeSnapshot.GaugeDataPointSnapshot.builder().value(value);
    labelMapper.ifPresent(
        mapper ->
            dataPointBuilder.labels(
                mapper.getLabels(
                    dropwizardName, Collections.emptyList(), Collections.emptyList())));
    return new GaugeSnapshot(metadata, Collections.singletonList(dataPointBuilder.build()));
  }

  /**
   * Export a histogram snapshot as a prometheus SUMMARY.
   *
   * @param dropwizardName metric name.
   * @param snapshot the histogram snapshot.
   * @param count the total sample count for this snapshot.
   * @param factor a factor to apply to histogram values.
   */
  MetricSnapshot fromSnapshotAndCount(
      String dropwizardName, Snapshot snapshot, long count, double factor, String helpMessage) {
    Quantiles quantiles =
        Quantiles.builder()
            .quantile(0.5, snapshot.getMedian() * factor)
            .quantile(0.75, snapshot.get75thPercentile() * factor)
            .quantile(0.95, snapshot.get95thPercentile() * factor)
            .quantile(0.98, snapshot.get98thPercentile() * factor)
            .quantile(0.99, snapshot.get99thPercentile() * factor)
            .quantile(0.999, snapshot.get999thPercentile() * factor)
            .build();

    MetricMetadata metadata =
        new MetricMetadata(PrometheusNaming.sanitizeMetricName(dropwizardName), helpMessage);
    SummarySnapshot.SummaryDataPointSnapshot.Builder dataPointBuilder =
        SummarySnapshot.SummaryDataPointSnapshot.builder().quantiles(quantiles).count(count);
    labelMapper.ifPresent(
        mapper ->
            dataPointBuilder.labels(
                mapper.getLabels(
                    dropwizardName, Collections.emptyList(), Collections.emptyList())));
    return new SummarySnapshot(metadata, Collections.singletonList(dataPointBuilder.build()));
  }

  /** Convert histogram snapshot. */
  MetricSnapshot fromHistogram(String dropwizardName, Histogram histogram) {
    return fromSnapshotAndCount(
        dropwizardName,
        histogram.getSnapshot(),
        histogram.getCount(),
        1.0,
        getHelpMessage(dropwizardName, histogram));
  }

  /** Export Dropwizard Timer as a histogram. Use TIME_UNIT as time unit. */
  MetricSnapshot fromTimer(String dropwizardName, Timer timer) {
    return fromSnapshotAndCount(
        dropwizardName,
        timer.getSnapshot(),
        timer.getCount(),
        1.0D / TimeUnit.SECONDS.toNanos(1L),
        getHelpMessage(dropwizardName, timer));
  }

  /** Export a Meter as a prometheus COUNTER. */
  MetricSnapshot fromMeter(String dropwizardName, Meter meter) {
    MetricMetadata metadata = getMetricMetaData(dropwizardName + "_total", meter);
    CounterSnapshot.CounterDataPointSnapshot.Builder dataPointBuilder =
        CounterSnapshot.CounterDataPointSnapshot.builder().value(meter.getCount());
    labelMapper.ifPresent(
        mapper ->
            dataPointBuilder.labels(
                mapper.getLabels(
                    dropwizardName, Collections.emptyList(), Collections.emptyList())));
    return new CounterSnapshot(metadata, Collections.singletonList(dataPointBuilder.build()));
  }

  @Override
  public MetricSnapshots collect() {
    MetricSnapshots.Builder metricSnapshots = MetricSnapshots.builder();

    registry
        .getGauges(metricFilter)
        .forEach(
            (name, gauge) -> {
              MetricSnapshot snapshot = fromGauge(name, gauge);
              if (snapshot != null) {
                metricSnapshots.metricSnapshot(snapshot);
              }
            });

    registry
        .getCounters(metricFilter)
        .forEach((name, counter) -> metricSnapshots.metricSnapshot(fromCounter(name, counter)));
    registry
        .getHistograms(metricFilter)
        .forEach(
            (name, histogram) -> metricSnapshots.metricSnapshot(fromHistogram(name, histogram)));
    registry
        .getTimers(metricFilter)
        .forEach((name, timer) -> metricSnapshots.metricSnapshot(fromTimer(name, timer)));
    registry
        .getMeters(metricFilter)
        .forEach((name, meter) -> metricSnapshots.metricSnapshot(fromMeter(name, meter)));

    return metricSnapshots.build();
  }

  public static Builder builder() {
    return new Builder();
  }

  // Builder class for DropwizardExports
  public static class Builder {
    private MetricRegistry registry;
    private MetricFilter metricFilter;
    private CustomLabelMapper labelMapper;

    private Builder() {
      this.metricFilter = MetricFilter.ALL;
    }

    public Builder dropwizardRegistry(MetricRegistry registry) {
      this.registry = registry;
      return this;
    }

    public Builder metricFilter(MetricFilter metricFilter) {
      this.metricFilter = metricFilter;
      return this;
    }

    public Builder customLabelMapper(CustomLabelMapper labelMapper) {
      this.labelMapper = labelMapper;
      return this;
    }

    DropwizardExports build() {
      if (registry == null) {
        throw new IllegalArgumentException("MetricRegistry must be set");
      }
      if (labelMapper == null) {
        return new DropwizardExports(registry, metricFilter);
      } else {
        return new DropwizardExports(registry, metricFilter, labelMapper);
      }
    }

    public void register() {
      register(PrometheusRegistry.defaultRegistry);
    }

    public void register(PrometheusRegistry registry) {
      DropwizardExports dropwizardExports = build();
      registry.register(dropwizardExports);
    }
  }
}