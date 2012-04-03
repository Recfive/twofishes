A coarse, splitting geocoder in scala, with a legacy implementation in python.

What is a Geocoder?
===================

A geocoder is a piece of software that translates from strings to coordinates. "New York, NY" to "40.74,  -74.0". This is an implementation of a coarse (city level, meaning it can't understand street addresses) geocoder that also supports splitting (breaking off the non-geocoded part in the final response).

Overview
========

This geocoder was designed around the geonames data, which is relatively small, and easy to parse in a short amount of time in a single thread without much post-processing. Geonames is a collection of simple text files that represent political features across the world. The geonames data has the nice property that all the features are listed with stable identifiers for their parents, the bigger political features that contain them (rego park -> queens county -> new york state -> united states). In one pass, we can build a database where each entry is a feature with a list of names for indexing, names for display, and a list of parents.

I use mongo to save state during the index building phase (so that, for instance, we can parse the alternateNames file, which adds name+lang pairs to features defined in a separate file, or adding the flickr bounding boxes). A final pass goes over the databse, dereferences ids and outputs two modestly sized hfiles. These two hfiles are all that is required for serving the data.

If we were doing heavier processing on the incoming data, a mapreduce that spits out hfiles might make more sense.

When we parse a query, we do a rough recursive descent parse, starting from the left. If being used to split geographic queries like "pizza new york" we expect the "what" to be on the left. All of the features found in a parse must be parents of the smallest 

The geocoder currently may return multiple valid parses, however, it only returns the longest possible parses. For "Springfield, US" we will return multiple features that match that query (there are dozens of springfields in the US). It will not return a parse of "Springfield" near "US" with only US geocoded if it can find a longer parse, but it will return multiple valid interpretations of the longest parse.

The Data
========

Geonames is great, but not perfect. Southeast Asia doesn't have the most comprehensive coverage. Geonames doesn't have bounding boxes, so we add some of those from http://code.flickr.com/blog/2011/01/08/flickr-shapefiles-public-dataset-2-0/ where possible. This file was generated by a now orphaned python script that lives at legacy-python/flickr/match_flickr.py. We attempted to geocode each feature name with the legacy python geocoder and then checked if the geonames point was within the bounds of the flickr feature. I could not get geojson parsing libraries to work in java, hence the use of the legacy code.

Additionally, the mapbox folks generated a mapping for us from OSM features with geometry to geonameids, however OSM has very low polygon/bounding box coverage.

Geonames is licensed under CC-BY http://www.geonames.org/. They take a pretty liberal interpretation of this and just ask for about page attribution if you make use of the data. 
Flickr shapefiles are public domain 

Requirements
============
*   Scala
*   [Mongo](http://www.mongodb.org/display/DOCS/Quickstart)
*   curl

First time setup
================
*   git clone git@github.com:foursquare/twofishes.git
*   cd twofish
*   ./init.sh
*   ./download-US.sh # or ./download-world.sh

Data import
===========
*   mongod --dbpath /local/directory/for/output/
*   ./init-database.sh # drops existing table and creates indexes
*   ./sbt "indexer/run-main com.foursquare.twofishes.importers.geonames.GeonamesParser --parse_country US --hfile_basepath /output/directory/for/finished/files/" # or ./sbt "indexer/run-main com.foursquare.twofishes.importers.geonames.GeonamesParser --parse_world true --hfile_basepath /output/directory"

Serving
=======
*   ./sbt  "server/run-main com.foursquare.twofishes.GeocodeFinagleServer --port 8080 --hfile_basepath /input/directory/for/finished/files/"
*   server should be responding to finagle-thrift on the port specified (8080 by default), and responding to http requests at the next port up: <http://localhost:8081/?query=rego+park+ny> <http://localhost:8081/static/geocoder.html#rego+park>
*   if you want to run vanilla thrift-rpc (not finagle). use class com.foursquare.twofishes.GeocodeThriftServer instead.
NOTE: mongod is not required for serving, only index building.


A better option is to run "./sbt server/assembly" and then use the resulting server/target/server-assembly-VERSION.jar. Serve that with java -jar JARFILE --hfile_basepath /directory

Performance
===========
Performance was only tested against the json frontend. Hitting the finagle would probably be a lot faster.

On a beefy AWS box, apachebench, running the same query over and over claims we can do 250 concurrent connections at 1500qps with sub-200ms latency @ 95%ile. There's a terrible drop-off in 98th percentile latency -- 3-4s. This might be java GC pauses, though I don't see terrible GC in profiling. Also, this workload is unrealistic.

httpperf lets us better control qps but not concurrency (why are the two major http benchmarking tools complementary with a gap between them?). On a more randomized workload, httperf shows says we can do 500qps with a median connection time of 4.5ms and an average response time of 8.9ms.

Future
======
I'd like to integrate more data from OSM and possibly an entire build solely from OSM. I'd also like to get supplemental data from the Foursquare database where possible. If I was feeling more US-centric, I'd parse the TIGER-line data for US polygons, but I'm expecting those to mostly be in OSM.

Also US-centric are zillow neighborhood polygons, also CC-by-SA. I might add an "attribution" field to the response for certain datasources. I'm not looking forward to writing a conflater with precedence for overlapping features from different data sets.

Legacy Python Server
====================
I originally implemented this as a weekend python hack in a few hundred lines ... then added some features ... then some more ... and then it was way too much gross python with no object model. So I rewrote it entirely in scala. If you want to try running the python server, use importer.py in legacy-python/ and geocoder.py in legacy-python/

Unrelated
=========
These are the two fishes I grilled the night I started coding the original python implementation <https://twitter.com/#!/whizziwig/statuses/154431957630066688>

