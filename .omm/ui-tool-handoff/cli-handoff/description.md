CLI에서 CTS·벤치마크·Dual로 이동할 때 MainCliBridge가 기존 Live 엔진을 닫습니다. close(done) 이후에도 같은 요청이 활성 상태이고 화면이 전면일 때만 다음 Activity를 엽니다. 다음 화면은 CommandCoordinator.continueHandover로 요청 소유권을 이어받습니다.
