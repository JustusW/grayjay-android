@queue @persistence
Feature: A queue is never lost by accident
  Closing the player or restarting the app parks the queue as "Last Queue", including which video
  was playing. Resuming it picks up at that video. Starting a new queue does not overwrite the parked
  one; only parking the new queue does.

  Background:
    Given I am playing a queue of "Video 01", "Video 02", "Video 03", "Video 04" starting at "Video 03"

  @new
  Scenario: Closing the player parks the queue where it was
    When I close the player
    And I resume the last queue
    Then "Video 03" is playing
    And the queue is "Video 01", "Video 02", "Video 03", "Video 04"

  @new
  Scenario: The parked queue survives an app restart
    When the app is restarted
    And I resume the last queue
    Then "Video 03" is playing
    And the queue is "Video 01", "Video 02", "Video 03", "Video 04"

  @new
  Scenario: Starting a new queue keeps the parked one until the new one is parked
    When I close the player
    And I add "Video 07" to the queue from the home feed
    Then the last queue is "Video 01", "Video 02", "Video 03", "Video 04"
    When I close the player
    Then the last queue is "Video 07"
