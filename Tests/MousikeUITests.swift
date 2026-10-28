import XCTest

final class MousikeUITests: XCTestCase {
    func testUnlockedFirstLaunchAndNavigation() {
        let app = XCUIApplication(); app.launch()
        let start = app.buttons["startListening"]
        if start.waitForExistence(timeout: 5) { start.tap() }
        XCTAssertTrue(app.buttons["Library"].waitForExistence(timeout: 10))
        app.buttons["Library"].tap()
        XCTAssertTrue(app.buttons["Import lossless from Files"].exists)
        app.buttons["Search"].tap()
        XCTAssertTrue(app.textFields["musicSearch"].exists)
        XCTAssertFalse(app.secureTextFields.firstMatch.exists)
    }
}
