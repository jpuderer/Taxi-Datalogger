#!/usr/bin/env python

# Copyright 2017 James Puderer. All Rights Reserved. 
# Copyright 2015 Google Inc. All Rights Reserved.
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Original source copied from Google tutorial available here:
#    https://github.com/GoogleCloudPlatform/bigquery-reverse-geolocation

"""
This script copies Google Cloud Pub/Sub messages from a subscription into 
a BigQuery table with the appropriate schema.  The messages should contain
row data formated as JSON with key/value pairs representing columns and
values.

The source subscription and destination table are configured in the setup.yaml
file containted in the same directory as the script.
"""
import sys
import base64
from apiclient import discovery
from dateutil.parser import parse
import httplib2
import yaml
import time
import datetime
import uuid
import json
import signal
import sys
from oauth2client import client as oauth2client

with open("setup.yaml", 'r') as  varfile:
    cfg = yaml.load(varfile)

PUBSUB_SCOPES = ['https://www.googleapis.com/auth/pubsub']
running_proc = True

def signal_term_handler(signal, frame):
    global running_proc
    print "Exiting application"
    running_proc = False
    sys.exit(0)

def create_pubsub_client(http=None):
    credentials = oauth2client.GoogleCredentials.get_application_default()
    if credentials.create_scoped_required():
        credentials = credentials.create_scoped(PUBSUB_SCOPES)
    if not http:
        http = httplib2.Http()
    credentials.authorize(http)
    return discovery.build('pubsub', 'v1', http=http)

def create_bigquery_client():
    credentials = oauth2client.GoogleCredentials.get_application_default()
    # Construct the service object for interacting with the BigQuery API.
    return discovery.build('bigquery', 'v2', credentials=credentials)

def stream_row_to_bigquery(bigquery, row,
                           num_retries=5):
    # Generate a unique row ID so retries
    # don't accidentally insert duplicates.
    insert_all_data = {
        'insertId': str(uuid.uuid4()),
        'rows': [{'json': row}]
    }
    return bigquery.tabledata().insertAll(
        projectId=cfg["env"]["PROJECT_ID"],
        datasetId=cfg["env"]["DATASET_ID"],
        tableId=cfg["env"]["TABLE_ID"],
        body=insert_all_data).execute(num_retries=num_retries)

def main(argv):
    client = create_pubsub_client()

    # You can fetch multiple messages with a single API call.
    batch_size = 100

    # Option to wait for some time until daily quotas are reset.
    wait_timeout = 2

    subscription = cfg["env"]["SUBSCRIPTION"]

    # Create a POST body for the Cloud Pub/Sub request.
    body = {
        # Setting ReturnImmediately to False instructs the API to wait
        # to collect the message up to the size of MaxEvents, or until
        # the timeout.
        'returnImmediately': False,
        'maxMessages': batch_size,
    }

    signal.signal(signal.SIGINT, signal_term_handler)
    while running_proc:
        # Pull messages from Cloud Pub/Sub
        resp = client.projects().subscriptions().pull(
            subscription=subscription, body=body).execute()

        received_messages = resp.get('receivedMessages')

        if received_messages is not None:
            ack_ids = []
            bq = create_bigquery_client()
            for received_message in received_messages:
                pubsub_message = received_message.get('message')
                if pubsub_message:
                    # get messages
                    msg = base64.b64decode(str(pubsub_message.get('data')))
                    print "Pulled: " + msg

                    # parse the message
                    row = json.loads(msg)

                    # save row to BigQuery
                    result = stream_row_to_bigquery(bq, row)

                    # Get the message's ack ID.
                    ack_ids.append(received_message.get('ackId'))

            # Create a POST body for the acknowledge request.
            ack_body = {'ackIds': ack_ids}

            # Acknowledge the message.
            client.projects().subscriptions().acknowledge(
                subscription=subscription, body=ack_body).execute()

if __name__ == '__main__':
            main(sys.argv)
