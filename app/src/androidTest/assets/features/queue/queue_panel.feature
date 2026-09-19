@queue @panel
Feature: The queue panel
  The queue panel is a place to manage the queue while watching: it shows which video is playing,
  it keeps up with the queue as it changes, and it stays open until the user closes it.

  @new
  Scenario: The playing video is marked in the queue panel
    Given I am playing a queue of "Video 01", "Video 02", "Video 03" starting at "Video 02"
    When I open the queue
    Then the queue panel lists "Video 01", "Video 02", "Video 03"
    And "Video 02" is marked as playing in the queue panel

  @new
  Scenario: The queue panel stays open when skipping to the next video
    Given I am playing a queue of "Video 01", "Video 02", "Video 03"
    When I open the queue
    And I skip to the next video
    Then "Video 02" is playing
    And the queue panel is open
    And "Video 02" is marked as playing in the queue panel

  @new
  Scenario: The queue panel stays open when a video finishes
    Given I am playing a queue of "Video 01", "Video 02", "Video 03"
    When I open the queue
    And the current video finishes
    Then "Video 02" is playing
    And the queue panel is open
    And "Video 02" is marked as playing in the queue panel

  @new
  Scenario: The queue panel keeps up with videos leaving the queue
    Given my Watch Later contains "Video 01", "Video 02", "Video 03"
    And I play my Watch Later
    When I open the queue
    And the current video finishes
    Then "Video 02" is playing
    And the queue panel lists "Video 02", "Video 03"

  @new
  Scenario: A single queued video still offers the queue
    When I add "Video 02" to the queue from the home feed
    Then "Video 02" is playing
    And the up next section is shown
    When I open the queue
    Then the queue panel lists "Video 02"
