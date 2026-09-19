@queue @tapping
Feature: Tapping a video while a queue is active
  Tapping a video anywhere in the app behaves the same way: it plays right away, is slotted in
  directly after the video that was playing, and the queue carries on from there afterwards.
  Nothing wipes the queue behind the user's back, and a video already in the queue is jumped to
  instead of being added a second time.

  Background:
    Given I am playing a queue of "Video 01", "Video 02", "Video 03"

  @new
  Scenario: A video tapped in the home feed plays next in line
    When I tap "Video 05" in the home feed
    Then "Video 05" is playing
    And the queue is "Video 01", "Video 05", "Video 02", "Video 03"
    And the next video is "Video 02"

  @new
  Scenario: The interrupted video is not replayed after the tapped one
    When I tap "Video 05" in the home feed
    And the current video finishes
    Then "Video 02" is playing

  @new
  Scenario: Tapping a video that is already queued jumps to it
    When I tap "Video 03" in the home feed
    Then "Video 03" is playing
    And the queue is "Video 01", "Video 02", "Video 03"

  @new
  Scenario: A video tapped on a channel page plays next in line
    When I open the channel "Beta Channel"
    And I tap "Video 08" on the channel page
    Then "Video 08" is playing
    And the queue is "Video 01", "Video 08", "Video 02", "Video 03"

  @new
  Scenario: A recommended video plays next in line
    When I tap the recommended video "Video 13"
    Then "Video 13" is playing
    And the queue is "Video 01", "Video 13", "Video 02", "Video 03"
    And the next video is "Video 02"

  @new
  Scenario: A video tapped in the watch history keeps the queue
    Given I watched "Video 06" earlier
    When I tap "Video 06" in my watch history
    Then "Video 06" is playing
    And the queue is "Video 01", "Video 06", "Video 02", "Video 03"
