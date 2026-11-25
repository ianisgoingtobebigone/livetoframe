import XCTest

@MainActor
final class GalleryGridSelectionUITests: XCTestCase {
    func testGridKeepsFiveColumnsAndSelectionInAllTab() throws {
        let app = XCUIApplication()
        app.launch()

        let gallery = app.scrollViews["photoGalleryScroll"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 15), "相册滚动区域没有出现")

        let tiles = app.descendants(matching: .any)
            .matching(NSPredicate(format: "identifier BEGINSWITH %@", "galleryPhotoTile-"))
        XCTAssertTrue(tiles.firstMatch.waitForExistence(timeout: 30), "照片网格没有加载照片")
        XCTAssertEqual(visibleColumnCount(in: gallery, tiles: tiles), 5, "照片网格应始终显示五列")

        let tile = tiles.firstMatch
        XCTAssertEqual(tile.value as? String, "未选中", "每次启动都应从空选择开始")
        let tileID = tile.identifier
        tile.tap()
        XCTAssertEqual(tile.value as? String, "已选中", "选择照片后应立即显示选中态")

        app.buttons["全部"].tap()
        XCTAssertTrue(tile.waitForExistence(timeout: 5), "切换到全部视图后照片应仍在网格中")
        XCTAssertEqual(tile.value as? String, "已选中", "全部视图应保留并显示照片选中态")
        XCTAssertEqual(visibleColumnCount(in: gallery, tiles: tiles), 5, "切换视图后网格仍应保持五列")

        app.terminate()
        app.launch()
        let relaunchedTile = app.descendants(matching: .any).matching(identifier: tileID).firstMatch
        XCTAssertTrue(relaunchedTile.waitForExistence(timeout: 30), "重启后原照片应仍在网格中")
        XCTAssertEqual(relaunchedTile.value as? String, "未选中", "重启后不应恢复未提交的选择")
    }

    func testDateRailJumpsToHistoricalPhotosInLargeLibrary() throws {
        let app = XCUIApplication()
        app.launch()
        let gallery = app.scrollViews["photoGalleryScroll"]
        XCTAssertTrue(gallery.waitForExistence(timeout: 20))
        let count = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@",
            "^[0-9,]+ 张.*")).firstMatch
        XCTAssertTrue(count.waitForExistence(timeout: 30))
        let digits = count.label.split(separator: " ").first?.filter(\.isNumber) ?? ""
        try XCTSkipUnless((Int(digits) ?? 0) >= 15_000, "此用例需要至少 15,000 张实况照片的真机相册")

        gallery.swipeUp(velocity: .slow)
        let handle = app.descendants(matching: .any).matching(identifier: "galleryDateScrollbar").firstMatch
        XCTAssertTrue(handle.exists)
        let end = gallery.coordinate(withNormalizedOffset: CGVector(dx: 0.96, dy: 0.97))
        handle.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5))
            .press(forDuration: 0.05, thenDragTo: end, withVelocity: .slow, thenHoldForDuration: 0.1)

        let latestHistoricalYear = Calendar.current.component(.year, from: Date()) - 2
        let year = (2000...latestHistoricalYear).map(String.init).joined(separator: "|")
        let historicalDay = app.staticTexts.matching(NSPredicate(format: "label MATCHES %@",
            "^(" + year + ")年.*")).firstMatch
        XCTAssertTrue(historicalDay.waitForExistence(timeout: 5), "松手后应显示几年前的真实相册日期标题")
        let returnedToRecent = XCTNSPredicateExpectation(predicate: NSPredicate(format: "exists == false"),
                                                        object: historicalDay)
        returnedToRecent.isInverted = true
        XCTAssertEqual(XCTWaiter.wait(for: [returnedToRecent], timeout: 2), .completed,
                       "松手并等快滚把手淡出后，历史日期仍应留在图库中")
    }

    private func visibleColumnCount(in gallery: XCUIElement, tiles: XCUIElementQuery) -> Int {
        let viewport = gallery.frame
        let midYs = tiles.allElementsBoundByIndex
            .filter { $0.isHittable && $0.frame.intersects(viewport) }
            .map { $0.frame.midY }
            .sorted()

        var rowCounts: [Int] = []
        var rowMidY: CGFloat?
        for midY in midYs {
            if let currentMidY = rowMidY, abs(midY - currentMidY) <= 8 {
                rowCounts[rowCounts.count - 1] += 1
            } else {
                rowMidY = midY
                rowCounts.append(1)
            }
        }
        return rowCounts.max() ?? 0
    }
}
