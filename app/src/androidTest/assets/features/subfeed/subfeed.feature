@subfeed
Feature: subfeed keeps the server informed without ever risking Watch Later
  The bridge and the subfeed plugin against the real subfeed server (its throw-away database, its worker polling a
  fake YouTube), reached as http://subfeed.wingert.internal (hosts file -> 127.0.0.1, adb reverse tcp:80).
  A long existing Watch Later must come through the first sync exactly as it was, and the server may only take
  videos out of it one at a time.

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
    And the server's queue is my Watch Later, order included
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
  Scenario: Removing many videos at once is refused on the server and on the phone
    Given subfeed is on and in sync
    When the server is sent its queue without 5 videos
    Then the server refuses the bulk removal and keeps its queue
    When 5 unfinished videos are removed on the server one by one
    And subfeed pulls the server's queue
    Then my Watch Later is exactly what it was before, order included
    And subfeed reports a refused bulk change

  @subfeed-single-removal
  Scenario: The server removing one video is applied
    Given subfeed is on and in sync
    When 1 unfinished video is removed on the server
    And subfeed pulls the server's queue
    Then my Watch Later lost exactly that video and kept the order of the rest

  @subfeed-restore
  Scenario: Watch Later can be restored from the server's snapshot
    Given subfeed is on and in sync
    When Watch Later is cut down to 10 videos on the phone
    Then the server's queue follows to the same 10 videos
    When I restore Watch Later from the server snapshot in the settings
    Then my Watch Later is exactly what it was before, order included
    And the server's queue follows to my Watch Later

  @subfeed-priority
  Scenario: A new video of a priority channel goes to the top and is notified
    Given subfeed is on and in sync
    And the server has polled the channels
    When "Gate Channel 1" is made a priority channel with the admin key
    And "Gate Channel 1" uploads "Breaking: a priority upload"
    Then the server puts "Breaking: a priority upload" at the top of its queue
    When the 15-minute subfeed worker runs
    Then a notification for "Breaking: a priority upload" is shown
    And my Watch Later starts with "Breaking: a priority upload"

  @subfeed-events
  Scenario: What I do in Grayjay reaches the server
    Given subfeed is on and in sync
    When I subscribe to "Gate Channel 4" and unsubscribe from "Gate Channel 2"
    And I watch "Gate video 040" up to 200 seconds
    And I add "Gate video 400" to Watch Later and remove "Gate video 020" from it
    Then the server knows "Gate Channel 4" and that "Gate Channel 2" is gone
    And the server got the position 200 of "Gate video 040"
    And the server's queue follows to my Watch Later
