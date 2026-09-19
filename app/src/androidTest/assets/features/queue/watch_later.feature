@queue @watch-later
Feature: Watch Later as a queue
  Playing Watch Later works through it: a video that has been watched to the end leaves Watch Later.
  A video that was merely interrupted or skipped past never does.

  Background:
    Given my Watch Later contains "Video 01", "Video 02", "Video 03"
    And I play my Watch Later

  @existing
  Scenario: A finished Watch Later video leaves Watch Later and the next one plays
    When the current video finishes
    Then "Video 02" is playing
    And my Watch Later is "Video 02", "Video 03"

  @new
  Scenario: Watching a recommendation does not remove the Watch Later video it interrupted
    When I tap the recommended video "Video 13"
    And the current video finishes
    Then my Watch Later is "Video 01", "Video 02", "Video 03"

  @new
  Scenario: Removing the playing video from the queue does not cost the next one
    When I open the queue
    And I remove "Video 01" from the queue
    And the current video finishes
    Then "Video 02" is playing
    And my Watch Later still contains "Video 02"

  @new
  Scenario: Watch Later does not inherit repeat from an earlier playlist
    Given I have a playlist "Mix" with "Video 07", "Video 08"
    And I play the playlist "Mix"
    And I turn repeat on
    And I close the player
    When I tap "Video 02" in my Watch Later
    Then "Video 02" is playing
    And repeat is off
