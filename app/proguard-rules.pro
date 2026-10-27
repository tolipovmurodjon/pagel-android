# A1: no custom rules yet. Room/Hilt/WorkManager ship their own consumer rules.

# Glance creates widget actions by reflection with no arguments (skill glance-appwidget-updates-and-memory).
-keep class * implements androidx.glance.appwidget.action.ActionCallback { public <init>(); }
