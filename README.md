# Spark Big Data Pipeline App

This project is a Spark Real Time Big Data Streaming Pipeline build in java. It has takes sales and tweets data from File, and HDFS source, transfers it through Kafka and aggregates it and publishes it to HDFS/JDBC sink.
This is completely scalabe in that inly the Kafka properties and number of threads in spark need to be changed if data volume or rate increases.

# Notes
Make sure to setup the localhost file in your windows machine.

# Reset offsets
To republish data with Kafka Connect, please delete the file /tmp/connect.offsets. This will remove all offsets maintained by Kafka connect and republish data from the first record. Similarly, you can delete any topic currently stored in Kafka with the following command. 

/usr/lib/kafka/bin/kafka-topics.sh --delete \
--zookeeper localhost:2181 \
--topic <<topic-name>>