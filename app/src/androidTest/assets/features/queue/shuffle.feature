@queue @shuffle
Feature: Shuffle
  Shuffle plays everything in the queue exactly once, in a random order that really is random,
  including which video comes first.

  @new
  Scenario: Shuffling Watch Later does not always start with the same video
    Given my Watch Later contains "Video 01", "Video 02", "Video 03", "Video 04", "Video 05", "Video 06"
    When I shuffle my Watch Later 5 times
    Then not every shuffle started with the same video

  @existing
  Scenario: Turning shuffle on keeps the playing video and every queued video
    Given I am playing a queue of "Video 01", "Video 02", "Video 03", "Video 04"
    When I turn shuffle on
    Then "Video 01" is playing
    And the queue contains exactly "Video 01", "Video 02", "Video 03", "Video 04"
