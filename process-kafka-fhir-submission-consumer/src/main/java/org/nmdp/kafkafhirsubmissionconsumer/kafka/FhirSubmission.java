package org.nmdp.kafkafhirsubmissionconsumer.kafka;

/**
 * Created by Andrew S. Brown, Ph.D., <andrew@nmdp.org>, on 8/9/17.
 * <p>
 * process-kafka-fhir-submission-consumer
 * Copyright (c) 2012-2017 National Marrow Donor Program (NMDP)
 * <p>
 * This library is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as published
 * by the Free Software Foundation; either version 3 of the License, or (at
 * your option) any later version.
 * <p>
 * This library is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; with out even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU Lesser General Public
 * License for more details.
 * <p>
 * You should have received a copy of the GNU Lesser General Public License
 * along with this library;  if not, write to the Free Software Foundation,
 * Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307  USA.
 * <p>
 * > http://www.fsf.org/licensing/licenses/lgpl.html
 * > http://www.opensource.org/licenses/lgpl-license.php
 */

import org.nmdp.kafkaconsumer.metrics.KafkaConsumerAggregate;
import org.nmdp.kafkafhirsubmissionconsumer.config.ApplicationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.yaml.snakeyaml.Yaml;

import com.google.common.annotations.VisibleForTesting;

import java.io.Closeable;
import java.io.File;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import com.codahale.metrics.JmxReporter;
import com.codahale.metrics.MetricRegistry;
import com.codahale.metrics.Slf4jReporter;
import com.codahale.metrics.health.HealthCheckRegistry;

import org.nmdp.kafkaconsumer.consumer.KafkaMessageConsumer;
import org.nmdp.kafkaconsumer.health.HealthReporter;
import org.nmdp.kafkaconsumer.health.KafkaConsumerHealthRegistry;
import org.nmdp.kafkafhirsubmissionconsumer.object.RouterObjectNameFactory;
import org.nmdp.kafkafhirsubmissionconsumer.config.RootConfiguration;

public class FhirSubmission {

    private static final Logger LOG = LoggerFactory.getLogger(FhirSubmission.class);
    private final List<Closeable> closeables = new ArrayList<>();

    private static void usage() {
        System.err.println("Usage: " + FhirSubmission.class.getSimpleName() + " <conf-file>");
        System.err.println();
        System.err.println("  conf-file     Configuration file to read (YAML)");
        System.err.println();
    }

    @VisibleForTesting
    public final MetricRegistry metrics;

    public FhirSubmission(ApplicationProperties applicationProperties) throws Exception {
        LOG.info(String.format("Creating kafka consumer from config file: %s.", applicationProperties.getConsumerConfigurationPath()));

        Yaml yaml = new Yaml();
        File file =  null;
        URL configUrl = null;
        RootConfiguration config;

        try {
            file = new File(applicationProperties.getConsumerConfigurationPath());
            configUrl = file.toURL();
            try (InputStream is = configUrl.openStream()) {
                config = yaml.loadAs(is, RootConfiguration.class);
                LOG.info(String.format("Config: %s", config.toString()));
            }
        } catch (Exception ex) {
            LOG.error("Error opening file",  ex);
            throw ex;
        }

        Runtime.getRuntime().addShutdownHook(new Thread(this::shutdown));

        metrics = new MetricRegistry();
        final HealthCheckRegistry health = new HealthCheckRegistry();

        metrics.addListener(new KafkaConsumerHealthRegistry(health, config.getAllowedConsumerDelayMs()));
        metrics.addListener(new KafkaConsumerAggregate(metrics));

        closeables.add(new HealthReporter(health, config.getHealthReportingIntervalMs()));

        final JmxReporter jmxReporter = JmxReporter.forRegistry(metrics)
                .createsObjectNamesWith(new RouterObjectNameFactory())
                .build();

        jmxReporter.start();
        closeables.add(jmxReporter);

        final Slf4jReporter slf4jReporter = Slf4jReporter.forRegistry(metrics).build();
        slf4jReporter.start(60, TimeUnit.SECONDS);
        closeables.add(slf4jReporter);

        try {
            Map<String, List<KafkaMessageConsumer>> connectors = config.initConnectors(closeables, metrics, applicationProperties);
            LOG.info("Connectors: {}", connectors.values().stream()
                    .flatMap(List::stream)
                    .map(KafkaMessageConsumer::toString)
                    .collect(Collectors.joining("\n  ", "\n  ", "")));
            LOG.info("Application started");
        } catch (Exception e) {
            LOG.error("Error starting app", e);
            shutdown();
            throw e;
        }
    }

    public void shutdown() {
        List<Closeable> shutdown = new ArrayList<>();
        shutdown.addAll(closeables);
        Collections.reverse(shutdown);
        shutdown.stream().forEach(c -> {
            try {
                c.close();
            } catch (Exception e) {
                LOG.error("Error shutting down " + c, e);
            }
        });
    }
}
