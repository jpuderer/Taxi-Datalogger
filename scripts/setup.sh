
# bq mk taxi_sensor_data
bq rm taxi-datalogger:taxi_sensor_data.log_entries
bq mk --schema entries_schema.json taxi-datalogger:taxi_sensor_data.log_entries

