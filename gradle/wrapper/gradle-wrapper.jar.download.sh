#!/bin/bash
curl -sL "https://services.gradle.org/distributions/gradle-8.5-bin.zip" -o /tmp/gradle-8.5-bin.zip
cd /tmp && unzip -qo gradle-8.5-bin.zip
/tmp/gradle-8.5/bin/gradle wrapper --gradle-version 8.5 --project-dir /Users/vemund/RiderProjects/shortshift-kiosk
rm -rf /tmp/gradle-8.5 /tmp/gradle-8.5-bin.zip
