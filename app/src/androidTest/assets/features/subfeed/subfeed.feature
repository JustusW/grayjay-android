@subfeed
Feature: subfeed keeps the server informed without ever risking Watch Later
  The bridge to the subfeed server, against a fake server on the host (adb reverse tcp:18765).
  A long existing Watch Later must come through the first sync exactly as it was, and the server
  may only take videos out of it one at a time.

  Background:
    Given the fake subfeed server is empty
    And my Watch Later holds 150 videos in a scrambled order
    And I have watched 6 of them to the end
    And I am subscribed to 3 channels

  @subfeed-first-sync
  Scenario: A long Watch Later survives the first sync unchanged, order included
    When I switch subfeed on
    Then a local backup with the 150 Watch Later videos in order was written before anything was sent
    And the server got a snapshot with the 150 Watch Later videos in order
    And the server's queue is my Watch Later, order included
    And my Watch Later is exactly what it was before, order included
    And the server got 3 subscriptions and the history

  @subfeed-bulk-refused
  Scenario: The server removing many videos at once is refused
    Given subfeed is on and in sync
    When the server's queue loses 5 unfinished videos
    And subfeed pulls the server's queue
    Then my Watch Later is exactly what it was before, order included
    And subfeed reports a refused bulk change

  @subfeed-single-removal
  Scenario: The server removing one video is applied, and finished ones may go
    Given subfeed is on and in sync
    When the server's queue loses 1 unfinished video and the 6 finished ones
    And subfeed pulls the server's queue
    Then my Watch Later lost exactly those 7 videos and kept the order of the rest

  @subfeed-reorder
  Scenario: A reorder on the server is applied
    Given subfeed is on and in sync
    When the server's queue is reversed
    And subfeed pulls the server's queue
    Then my Watch Later is in the server's order

  @subfeed-restore
  Scenario: Watch Later can be restored from the server snapshot
    Given subfeed is on and in sync
    When Watch Later is cut down to 10 videos on the phone
    Then the server's queue follows to the same 10 videos
    When I restore Watch Later from the server snapshot in the settings
    Then my Watch Later is exactly what it was before, order included
    And the server's queue follows to my Watch Later

  @subfeed-events
  Scenario: What I do in Grayjay reaches the server
    Given subfeed is on and in sync
    When I subscribe to "New Channel" and unsubscribe from "Channel 2"
    And I watch "Video 01" for a while and pause
    And I add "Video 07" to Watch Later and remove "Video 20" from it
    Then the server knows about the subscription to "New Channel" and that "Channel 2" is gone
    And the server got the position I paused "Video 01" at
    And the server's queue follows to my Watch Later
