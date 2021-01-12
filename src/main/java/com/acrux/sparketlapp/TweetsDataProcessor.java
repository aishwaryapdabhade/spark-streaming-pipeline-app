/*
 * Java Class that would subscribe to US Sales events and update the exec summary Table
 */

package com.acrux.sparketlapp;

import java.sql.Connection;
import java.sql.DriverManager;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

import org.apache.commons.lang.StringUtils;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.spark.SparkConf;
import org.apache.spark.SparkContext;
import org.apache.spark.api.java.JavaRDD;
import org.apache.spark.api.java.JavaSparkContext;
import org.apache.spark.api.java.function.Function;
import org.apache.spark.api.java.function.Function2;
import org.apache.spark.api.java.function.VoidFunction;
import org.apache.spark.streaming.Durations;
import org.apache.spark.streaming.api.java.JavaDStream;
import org.apache.spark.streaming.api.java.JavaInputDStream;
import org.apache.spark.streaming.api.java.JavaStreamingContext;
import org.apache.spark.streaming.kafka010.ConsumerStrategies;
import org.apache.spark.streaming.kafka010.KafkaUtils;
import org.apache.spark.streaming.kafka010.LocationStrategies;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

//
public class TweetsDataProcessor {
	public static void main(String[] args) {

		try {
			// Setup log levels so there is no slew of info logs
			Logger.getLogger("org").setLevel(Level.ERROR);

			// Start a spark instance and get a context
			SparkConf conf = new SparkConf()
					.setMaster("local[2]").setAppName("SparkETLApp");

			// Setup a streaming context.
			JavaStreamingContext streamingContext 
				= new JavaStreamingContext(conf, Durations.seconds(3));

			// Create a map of Kafka params
			Map<String, Object> kafkaParams 
				= new HashMap<String, Object>();
			// List of Kafka brokers to listen to.
			kafkaParams.put("bootstrap.servers", 
					"192.168.1.100:9092");
			kafkaParams.put("key.deserializer", 
					StringDeserializer.class);
			kafkaParams.put("value.deserializer", 
					StringDeserializer.class);
			kafkaParams.put("group.id", 
					"use_a_separate_group_id_for_each_stream");
			// Do you want to start from the earliest record or the latest?
			kafkaParams.put("auto.offset.reset", "earliest");
			kafkaParams.put("enable.auto.commit", false);

			// List of topics to listen to.
			Collection<String> topics 
				= Arrays.asList("use-case-tweets");

			// Create a Spark DStream with the kafka topics.
			final JavaInputDStream<ConsumerRecord<String, String>> stream 
				= KafkaUtils.createDirectStream(
					streamingContext, LocationStrategies.PreferConsistent(),
					ConsumerStrategies.<String, String>Subscribe(topics, kafkaParams));

			// Setup accumulator
			SparkContext sc = JavaSparkContext
					.toSparkContext(streamingContext.sparkContext());
			AccuMap totalTweetsMap = new AccuMap();
			sc.register(totalTweetsMap);
			AccuMap positiveTweetsMap = new AccuMap();
			sc.register(positiveTweetsMap);
			

			// Setup a DB Connection to save summary
			Class.forName("com.mysql.jdbc.Driver").newInstance();
			Connection execConn = DriverManager
					.getConnection("jdbc:mysql://192.168.1.100/exec_reports?user=aishw&password=aishw");

			// Setup a processing map function that only returns the payload.
			JavaDStream<String> retval = stream.map(new Function<ConsumerRecord<String, String>, String>() {

				private static final long serialVersionUID = 1L;

				public String call(ConsumerRecord<String, String> record) {

					try {

						//Convert the payload to a Json node and then extract relevant data.
						String jsonString = record.value();

						ObjectMapper objectMapper = new ObjectMapper();
						JsonNode docRoot = objectMapper.readTree(jsonString);
						String payload = docRoot.get("payload").asText();
						
						Long timestamp = Long.valueOf(StringUtils.left(payload, 13));
						String tweet = StringUtils.mid(payload, 14, payload.length() - 14  );
						SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:00:00");
						String timestampStr = sdf.format(new Date(timestamp));

						System.out.println("Records extracted " +timestamp + " " + tweet);
						
						//Add for total tweets.
						Map<String, Double> dataMap = new HashMap<String, Double>();
						dataMap.put(timestampStr, 1.0);
						//Add the map to the accumulator
						totalTweetsMap.add(dataMap);
						
						//Add for positive tweets
						if ( isPositiveTweet(tweet)) {
							Map<String, Double> dataMap2 = new HashMap<String, Double>();
							dataMap2.put(timestampStr, 1.0);
							//Add the map to the accumulator
							positiveTweetsMap.add(dataMap);
						}


					} catch (Exception e) {
						e.printStackTrace();
					}

					return record.value();
				}
			});

			//Output operation required to trigger all transformations.
			retval.print();

			//Executes at the Driver. Saves summarized data to the database.
			retval.foreachRDD(new VoidFunction<JavaRDD<String>>() {

				@Override
				public void call(JavaRDD<String> arg0) throws Exception {
					System.out.println("executing foreachRDD");
					System.out.println("Mapped values " + totalTweetsMap.value());

					Iterator dIterator = totalTweetsMap.value().keySet().iterator();
					while (dIterator.hasNext()) {
						String salesDate = (String) dIterator.next();
						String updateSql = "UPDATE exec_summary SET TWEETS = TWEETS + " + totalTweetsMap.value().get(salesDate)
								+ " WHERE REPORT_DATE = '" + salesDate + "'";
						System.out.println(updateSql);
						execConn.createStatement().executeUpdate(updateSql);
					}

					totalTweetsMap.reset();
					
					Iterator pIterator = positiveTweetsMap.value().keySet().iterator();
					while (pIterator.hasNext()) {
						String salesDate = (String) pIterator.next();
						String updateSql = "UPDATE exec_summary SET TWEETS_POSITIVE = TWEETS_POSITIVE + " + positiveTweetsMap.value().get(salesDate)
								+ " WHERE REPORT_DATE = '" + salesDate + "'";
						System.out.println(updateSql);
						execConn.createStatement().executeUpdate(updateSql);
					}

					positiveTweetsMap.reset();
				}

			});

			// Start streaming.
			streamingContext.start();

			try {
				streamingContext.awaitTermination();
			} catch (InterruptedException e) {
				e.printStackTrace();
			}

			// streamingContext.close();

			// Keep the program alive.
			while (true) {
				try {
					Thread.sleep(1000);
				} catch (InterruptedException e) {
					e.printStackTrace();
				}
			}
		} catch (Exception e) {

			e.printStackTrace();
		}
	}
	
	//A primitive sentiment finder
	private static boolean isPositiveTweet(String tweet) {
		
		String[] positiveWords = { "cool", "great" };
		
		for ( String pWord : positiveWords) {
			if ( tweet.contains(pWord)) {
				return true;
			}
		}
		
		return false;
	}

}
