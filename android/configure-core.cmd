@echo off
setlocal
set "MINGW=%LOCALAPPDATA%\mingw\mingw64\bin"
set "GITBINS=C:\Program Files\Git\cmd;C:\Program Files\Git\bin;C:\Program Files\Git\usr\bin;C:\Program Files\Git\mingw64\bin"
set "PATH=%MINGW%;%GITBINS%;%PATH%"
set "GIT_EXEC_PATH=C:\Program Files\Git\mingw64\libexec\git-core"

set "CMAKE_EXE=%~1"
set "NINJA_EXE=%~2"
set "NDK_DIR=%~3"
set "BUILD_DIR=%~4"
shift /4

"%CMAKE_EXE%" -S "%~dp0.." -B "%BUILD_DIR%" -G Ninja ^
  -DCMAKE_TOOLCHAIN_FILE="%NDK_DIR%\build\cmake\android.toolchain.cmake" ^
  -DANDROID_ABI=arm64-v8a ^
  -DANDROID_PLATFORM=android-29 ^
  -DCMAKE_BUILD_TYPE=Release ^
  -DCMAKE_MAKE_PROGRAM="%NINJA_EXE%" ^
  -DUSE_NATIVE_INSTRUCTIONS=OFF ^
  -DUSE_SDL=OFF ^
  -DUSE_SYSTEM_SDL=OFF ^
  -DUSE_GAMEMODE=OFF ^
  -DUSE_SYSTEM_LIBUSB=OFF ^
  -DUSE_SYSTEM_CURL=OFF ^
  -DUSE_SYSTEM_OPENCV=OFF ^
  -DUSE_SYSTEM_FFMPEG=OFF ^
  -DUSE_SYSTEM_ZLIB=ON ^
  -DUSE_DISCORD_RPC=OFF ^
  -DUSE_FAUDIO=OFF ^
  -DUSE_LIBEVDEV=OFF ^
  -DWITH_LLVM=OFF ^
  -DBUILD_LLVM=OFF ^
  -DSTATIC_LINK_LLVM=OFF ^
  -DLLVM_DIR="%NDK_DIR%\toolchains\llvm\prebuilt\windows-x86_64\lib\cmake\llvm" ^
  -DUSE_LTO=OFF ^
  -DASMJIT_NO_SHM_OPEN=ON ^
  -DPython3_EXECUTABLE="%LOCALAPPDATA%\Programs\Python\Python312\python.exe" ^
  -DLLVM_HOST_TRIPLE=aarch64-linux-android ^
  "-DCROSS_TOOLCHAIN_FLAGS_NATIVE=-DCMAKE_C_COMPILER=%MINGW%\gcc.exe;-DCMAKE_CXX_COMPILER=%MINGW%\g++.exe" ^
  %*
exit /b %ERRORLEVEL%