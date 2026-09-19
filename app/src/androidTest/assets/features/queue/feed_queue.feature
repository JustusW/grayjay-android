@queue @feed-queue
Feature: Playing a feed as a queue
  "Play feed as queue" plays the chosen video and then continues down the feed from there.

  @new
  Scenario: The feed queue starts at the chosen video and continues downwards
    When I choose "Play feed as queue" for "Video 04" in the home feed
    Then "Video 04" is playing
    And the queue is "Video 04", "Video 05", "Video 06", "Video 07", "Video 08"
