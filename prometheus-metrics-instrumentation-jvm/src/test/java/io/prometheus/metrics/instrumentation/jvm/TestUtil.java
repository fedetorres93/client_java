package io.prometheus.metrics.instrumentation.jvm;

import io.prometheus.metrics.expositionformats.OpenMetricsTextFormatWriter;
import io.prometheus.metrics.model.snapshots.EscapingScheme;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static io.prometheus.metrics.model.snapshots.PrometheusNaming.nameEscapingScheme;

class TestUtil {

  static String convertToOpenMetricsFormat(MetricSnapshots snapshots) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    OpenMetricsTextFormatWriter writer = new OpenMetricsTextFormatWriter(true, true);
    nameEscapingScheme = EscapingScheme.NO_ESCAPING;
    writer.write(out, snapshots);
    return out.toString(StandardCharsets.UTF_8.name());
  }
}
