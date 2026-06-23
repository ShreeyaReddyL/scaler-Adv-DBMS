@echo off
echo ===============================================
echo  MiniDB - Compiling Java Sources
echo ===============================================

:: Create output directory
if not exist "out" mkdir out

:: Compile all Java files
echo Compiling...
javac -d out -sourcepath src src\minidb\catalog\Catalog.java src\minidb\storage\Tuple.java src\minidb\storage\Page.java src\minidb\storage\DiskManager.java src\minidb\storage\BufferPool.java src\minidb\storage\HeapFile.java src\minidb\index\BPlusTree.java src\minidb\parser\SQLParser.java src\minidb\transaction\LockManager.java src\minidb\transaction\TransactionManager.java src\minidb\recovery\WALManager.java src\minidb\mvcc\MVCCManager.java src\minidb\optimizer\QueryOptimizer.java src\minidb\executor\QueryExecutor.java src\minidb\MiniDB.java

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo Compilation FAILED!
    pause
    exit /b 1
)

echo.
echo Compilation SUCCESSFUL!
echo.
echo Run options:
echo   run.bat              - Interactive mode
echo   run.bat --demo       - Demo with sample data
echo   run.bat --benchmark  - 2PL vs MVCC benchmarks
echo   run.bat --recover    - Crash recovery
echo   run.bat --crash-demo - Crash recovery demo
echo.
