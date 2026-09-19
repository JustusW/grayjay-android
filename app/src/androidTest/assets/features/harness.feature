@harness
Feature: Test harness
  The suite runs against a local fixture source instead of real platforms,
  so every scenario sees the same channels, videos and recommendations.

  Scenario: The fixture source feeds the home tab
    Then the home feed shows "Video 01"

  @new
  Scenario: Every kind of feed item the fixture serves can be shown
    Then the home feed contains "Video 08"
    And the home feed contains "Nested 01"
