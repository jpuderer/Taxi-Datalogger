This directory contains three useful things:
* pull_taxi_pubsub.py - A script for pulling data from the Pub/Sub subscription, and publishing them to your the BigQuery table.
* heatmap.html - An HTML page that diplays a Google map with heatmap overlay of your data.
* entries_schema.json - JSON scheme for creating the BigQuery table used in this project.

Both require some configuration changes to work with your project.

Code and ideas from the following Google tutorial, was used as a basis for the script and HTML page:
https://github.com/GoogleCloudPlatform/bigquery-reverse-geolocation/blob/master/README.md

-------

Below are some helpful commands setting up the BigQuery tables from the command line:

### Create the dataset:
  bq mk taxi_sensor_data

### Create the table (using the included schema):
  bq mk --schema entries_schema.json <your-project>:taxi_sensor_data.log_entries

### Delete the table (useful when you want to start fresh):
  bq rm <your-project>:taxi_sensor_data.log_entries

