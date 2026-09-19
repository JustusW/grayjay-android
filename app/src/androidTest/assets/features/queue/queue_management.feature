@queue @management
Feature: Managing the queue directly
  Videos can be placed exactly where they are wanted: next, last or first, both when adding them
  from anywhere in the app and when rearranging what is already queued. Nothing is ever duplicated.

  Background:
    Given I am playing a queue of "Video 01", "Video 02", "Video 03", "Video 04", "Video 05" starting at "Video 03"

  @existing
  Scenario: Add to queue appends to the end
    When I add "Video 07" to the queue from the home feed
    Then the queue is "Video 01", "Video 02", "Video 03", "Video 04", "Video 05", "Video 07"
    And "Video 03" is playing

  @new
  Scenario: Play next from the home feed puts a video right after the current one
    When I choose "Play next" for "Video 07" in the home feed
    Then the queue is "Video 01", "Video 02", "Video 03", "Video 07", "Video 04", "Video 05"
    And "Video 03" is playing
    And the next video is "Video 07"

  @new
  Scenario: Play next from the home feed moves an already queued video instead of duplicating it
    When I choose "Play next" for "Video 05" in the home feed
    Then the queue is "Video 01", "Video 02", "Video 03", "Video 05", "Video 04"
    And the next video is "Video 05"

  @new
  Scenario: Play next inside the queue
    When I open the queue
    And I choose "Play next" for "Video 05" in the queue
    Then the queue is "Video 01", "Video 02", "Video 03", "Video 05", "Video 04"
    And "Video 03" is playing
    And the next video is "Video 05"

  @new
  Scenario: Play last inside the queue
    When I open the queue
    And I choose "Play last" for "Video 01" in the queue
    Then the queue is "Video 02", "Video 03", "Video 04", "Video 05", "Video 01"
    And "Video 03" is playing
    And the next video is "Video 04"

  @new
  Scenario: Play first inside the queue
    When I open the queue
    And I choose "Play first" for "Video 05" in the queue
    Then the queue is "Video 05", "Video 01", "Video 02", "Video 03", "Video 04"
    And "Video 03" is playing
    And the next video is "Video 04"

  @existing
  Scenario: Tapping a queued video plays it
    When I open the queue
    And I tap "Video 05" in the queue
    Then "Video 05" is playing
    And the queue is "Video 01", "Video 02", "Video 03", "Video 04", "Video 05"

  @existing
  Scenario: Removing a video that is not playing
    When I open the queue
    And I remove "Video 04" from the queue
    Then the queue is "Video 01", "Video 02", "Video 03", "Video 05"
    And "Video 03" is playing
    And the next video is "Video 05"

  @new
  Scenario: Removing the playing video does not skip the one after it
    When I open the queue
    And I remove "Video 03" from the queue
    Then the queue is "Video 01", "Video 02", "Video 04", "Video 05"
    And "Video 03" is playing
    And the next video is "Video 04"
    When the current video finishes
    Then "Video 04" is playing
