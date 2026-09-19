@harness
Feature: Test harness
  The suite runs against a local fixture source instead of real platforms,
  so every scenario sees the same channels, videos and recommendations.

  Scenario: The fixture source feeds the home tab
    Then the home feed shows "Video 01"
