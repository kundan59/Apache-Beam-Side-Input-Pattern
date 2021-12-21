package org.knoldus.beam;

import org.apache.beam.sdk.Pipeline;
import org.apache.beam.sdk.io.TextIO;
import org.apache.beam.sdk.options.PipelineOptions;
import org.apache.beam.sdk.options.PipelineOptionsFactory;
import org.apache.beam.sdk.transforms.*;
import org.apache.beam.sdk.values.KV;
import org.apache.beam.sdk.values.PCollection;
import org.apache.beam.sdk.values.PCollectionView;
import org.apache.beam.sdk.values.TypeDescriptors;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.joda.time.LocalDateTime;
import org.joda.time.format.DateTimeFormat;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;


public class BeamSideInput {

    private static final String CSV_HEADER = "Date,Open,High,Low,Close,Adj Close,Volume";

    public static void main(String[] args) {

        PipelineOptions pipelineOptions = PipelineOptionsFactory.create();
        Pipeline pipeline = Pipeline.create(pipelineOptions);

        PCollection<String> readingGoogleStock = pipeline.apply("ReadingGoogleStock", TextIO
                .read()
                .from("src/main/resources/source/google_stock_20202.csv"))
                .apply("FilteringHeader", Filter
                        .by((String line) -> !line.isEmpty() && !line.equals(CSV_HEADER)));

        PCollectionView<Double> averageAdjustableClosing = readingGoogleStock
                .apply("ExtractAdjustableClosingPrice", FlatMapElements
                        .into(TypeDescriptors.doubles())
                        .via((String line) -> Collections.singletonList(Double.parseDouble(line.split(",")[5]))))
                .apply("GlobalAverageAdjustableClosing", Combine.globally(new Average()).asSingletonView());


        PCollection<KV<Integer, Double>> monthClosingPricesKV = readingGoogleStock
                .apply("ExtractMonthPricesKV", MapElements
                        .into(TypeDescriptors.kvs(TypeDescriptors.integers(), TypeDescriptors.doubles()))
                        .via((String line) -> {
                            String[] fields = line.split(",");

                            final DateTime utcDateTime = LocalDateTime.parse(fields[0].trim(),
                                    DateTimeFormat.forPattern("yyyy-MM-ss")).toDateTime(DateTimeZone.forID("UTC"));

                            return KV.of(utcDateTime.getMonthOfYear(), Double.parseDouble(fields[5]));
                        }))
                .apply("AverageMonthPrice", Combine.perKey(new Average()));

        monthClosingPricesKV.apply("SideInput", ParDo.of(new DoFn<KV<Integer, Double>, Void>() {

            @ProcessElement
            public void processElement(ProcessContext processContext) {

                Double globalAverageValue = processContext.sideInput(averageAdjustableClosing);
                if (processContext.element().getValue() >= globalAverageValue) {
                    System.out.println("Month " + processContext.element().getKey() + " has average closing price"
                            + processContext.element().getValue() +
                            " greater than global adjustable closing price :" + globalAverageValue);
                }
            }
        }).withSideInputs(averageAdjustableClosing));

        pipeline.run().waitUntilFinish();
    }

    private static class Average implements SerializableFunction<Iterable<Double>, Double> {

        @Override
        public Double apply(Iterable<Double> globalAdjustableClosePrices) {


            List<Double> collect = StreamSupport.stream(globalAdjustableClosePrices
                    .spliterator(), false)
                    .collect(Collectors.toList());
            double sum = collect.stream().mapToDouble(i -> i).sum();

            return sum /collect.size();
        }
    }
}
