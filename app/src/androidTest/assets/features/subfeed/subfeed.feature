@subfeed
Feature: subfeed keeps the server informed and never changes Grayjay on its own
  The bridge and the subfeed plugin against the real subfeed server (its throw-away database, its worker polling a
  fake YouTube), reached as http://subfeed.wingert.internal (hosts file -> 127.0.0.1, adb reverse tcp:80). The server
  runs as deployed: its queue read only (SUBFEED_QUEUE_READ_ONLY on, it keeps the phone's list as its copy).
  The bridge is read only too: a long existing Watch Later and the playlists come through every sync exactly as they
  were, whatever the server's queue says. Only a restore the user confirms in the settings writes.

  Background:
    Given the subfeed server is empty and its fake YouTube has 3 channels
    And my Watch Later holds 150 YouTube videos in a scrambled order
    And I have watched 6 of them to the end
    And I am subscribed to the 3 channels

  @subfeed-first-sync
  Scenario: A long Watch Later survives the first sync unchanged, order included
    When I switch subfeed on
    Then a local backup with the 150 Watch Later videos in order was written before anything was sent
    And the server stored a snapshot with the 150 Watch Later videos in order
    And the server's copy of the queue is my Watch Later, order included
    And my Watch Later is exactly what it was before, order included
    And the server has the 3 channels and the 6 finished videos as watched

  @subfeed-plugin
  Scenario: The subfeed plugin installs from the server, signed, and shows the feed on Home
    Given subfeed is on and in sync
    And the server has polled the channels
    When I open the plugin install page for "http://subfeed.wingert.internal/plugin/config.json"
    Then the install page shows the subfeed plugin without a signature warning
    When I install it
    Then Home shows the subfeed feed with my queued videos marked

  @subfeed-bulk-refused
  Scenario: The read-only server refuses every change to its queue, and Watch Later stays as it is
    Given subfeed is on and in sync
    When the server is sent its queue without 5 videos
    Then the server refuses it as read only and keeps its copy
    When subfeed syncs
    Then my Watch Later is exactly what it was before, order included

  @subfeed-single-removal
  Scenario: A server queue that differs from Watch Later changes nothing on the phone
    Given the server's queue is not read only
    And subfeed is on and in sync
    When 1 unfinished video is removed on the server
    And the server's queue is put in reverse order
    And subfeed syncs
    Then my Watch Later is exactly what it was before, order included

  @subfeed-restore
  Scenario: Watch Later can be restored from the server's snapshot
    Given subfeed is on and in sync
    When Watch Later is cut down to 10 videos on the phone
    Then the server's copy follows to the same 10 videos
    When I restore Watch Later from the server snapshot in the settings
    Then my Watch Later is exactly what it was before, order included
    And the server's copy follows to my Watch Later

  @subfeed-priority
  Scenario: A new video of a priority channel is notified and Watch Later stays as it is
    Given subfeed is on and in sync
    And the server has polled the channels
    When "Gate Channel 1" is made a priority channel with the admin key
    And "Gate Channel 1" uploads "Breaking: a priority upload"
    Then the server lists "Breaking: a priority upload" as a new priority video and keeps its queue
    When the 15-minute subfeed worker runs
    Then a notification for "Breaking: a priority upload" is shown
    And my Watch Later is exactly what it was before, order included

  @subfeed-events
  Scenario: What I do in Grayjay reaches the server
    Given subfeed is on and in sync
    When I subscribe to "Gate Channel 4" and unsubscribe from "Gate Channel 2"
    And I watch "Gate video 040" up to 200 seconds
    And I add "Gate video 400" to Watch Later and remove "Gate video 020" from it
    Then the server knows "Gate Channel 4" and that "Gate Channel 2" is gone
    And the server got the position 200 of "Gate video 040"
    And the server's copy follows to my Watch Later

  @subfeed-playlists
  Scenario: The playlists reach the server once after the update, untouched, and come back by a confirmed restore
    Given I have 10 playlists, two of them named "queue" and one with 80 videos
    And subfeed did its first sync with a version that sent no playlists
    When the app starts with this version
    Then the first thing sent is a snapshot "playlists" with all 10 playlists in order, also as a local backup
    And no playlist changed
    When subfeed syncs
    Then no further playlists snapshot was sent
    When I delete the playlist "Toolroom Takeover 2026" and remove a video from "Fu"
    And I restore playlists from the server snapshot in the settings
    Then "Toolroom Takeover 2026" is back with its id and its videos
    And "Fu" is as I changed it and "Fu (restored <today>)" holds its videos from the snapshot
    And the other playlists did not change
    When I delete the playlist "Test" and restore playlists from the local backup in the settings
    Then "Test" is back with its id and its videos
    And there is one "Fu (restored <today>)"
