@queue @basics
Feature: Queue basics
  What already works today and has to keep working through the queue rework.

  @existing
  Scenario: A tapped video just plays when there is no queue
    When I tap "Video 04" in the home feed
    Then "Video 04" is playing
    And there is no queue

  @existing
  Scenario: Adding to the queue while nothing is playing starts playback
    When I add "Video 02" to the queue from the home feed
    Then "Video 02" is playing

  @existing
  Scenario: Adding to the queue while a video plays starts a queue behind it
    Given I am watching "Video 01"
    When I add "Video 02" to the queue from the home feed
    And I add "Video 03" to the queue from the home feed
    Then "Video 01" is playing
    And the queue is "Video 01", "Video 02", "Video 03"
    And the next video is "Video 02"

  @existing
  Scenario: A finished video is followed by the next one in the queue
    Given I am playing a queue of "Video 01", "Video 02", "Video 03"
    When the current video finishes
    Then "Video 02" is playing
    And the next video is "Video 03"

  @existing
  Scenario: Skipping forward and back through the queue
    Given I am playing a queue of "Video 01", "Video 02", "Video 03"
    When I skip to the next video
    Then "Video 02" is playing
    When I skip to the previous video
    Then "Video 01" is playing

  @existing
  Scenario: The end of the queue is announced
    Given I am playing a queue of "Video 01", "Video 02" starting at "Video 02"
    Then the up next section says the end of the queue is reached

  @existing
  Scenario: Adding a video that is already queued is refused
    Given I am playing a queue of "Video 01", "Video 02", "Video 03"
    When I add "Video 02" to the queue from the home feed
    Then the queue is "Video 01", "Video 02", "Video 03"
