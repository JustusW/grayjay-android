@playback
Feature: Playback progress
  The time shown in the player and the position a video resumes from follow the playing video,
  whichever way the player is shown.

  Background:
    Given I am watching "Video 01"

  @existing
  Scenario: Progress advances in the normal player
    Then the playback time keeps advancing
    And the resume position keeps advancing

  @new
  Scenario: Progress keeps advancing in fullscreen
    When I switch to fullscreen
    Then the playback time keeps advancing
    And the resume position keeps advancing

  @new
  Scenario: Progress keeps advancing after leaving fullscreen
    When I switch to fullscreen
    And I leave fullscreen
    Then the playback time keeps advancing
    And the resume position keeps advancing
