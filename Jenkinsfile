// =========================================================================
// 배포 파이프라인 (itda-deploy 잡에서 사용)
//
// 트리거:
//   - release/* 브랜치 push → 변경된 서비스만 rc 태그로 빌드·배포 (검증 배포)
//   - v* 태그 push          → 세 서비스 전부 태그명 그대로 빌드·배포 (릴리스 확정)
//   - dev/main 병합은 배포를 일으키지 않는다 (MR 검증만 — Jenkinsfile.mr)
//
// 배포 실행: 빌드는 docker.sock으로 호스트 Docker에서 수행하고,
//   deploy.sh(sudo nginx reload 포함)는 호스트에서만 돌 수 있으므로
//   자기 호스트에 SSH(ec2-deploy-key)로 접속해 실행한다.
//   → 사람이 수동 배포할 때와 100% 같은 스크립트, 같은 경로.
// =========================================================================
// Mattermost 알림 — 전송 실패는 로그만 남기고 빌드 결과에 영향을 주지 않는다
def notifyMM(String text) {
    try {
        withCredentials([string(credentialsId: 'mattermost-webhook', variable: 'MM_URL')]) {
            writeFile file: '.mm-payload.json', text: groovy.json.JsonOutput.toJson([text: text])
            sh 'curl -sf -X POST -H "Content-Type: application/json" -d @.mm-payload.json "$MM_URL" >/dev/null; rm -f .mm-payload.json'
        }
    } catch (e) {
        echo "Mattermost 알림 실패(무시): ${e}"
    }
}

pipeline {
    agent any

    options {
        disableConcurrentBuilds()            // 배포 중 배포 금지 (.env/inc 동시 수정 방지)
        timestamps()
        timeout(time: 40, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    environment {
        DEPLOY_HOST   = 'host.docker.internal'            // Jenkins 컨테이너에서 본 자기 호스트
        DEPLOY_SCRIPT = '/home/ubuntu/app/scripts/deploy.sh'
        GMS_URI       = 'https://gms.ssafy.io/gmsapi/api.openai.com/v1/chat/completions'
        GMS_MODEL     = 'gpt-5.4-mini'                    // 배포 알림의 변경 요약용
    }

    stages {

        // ── 1. 무엇을 어떤 태그로 배포할지 결정 ──────────────────────
        stage('Plan') {
            steps {
                script {
                    env.DEPLOY_SERVICES = ''   // 비어 있으면 이후 스테이지 전부 스킵

                    // 브랜치/태그 삭제 이벤트 가드 (after가 전부 0 = 삭제 push)
                    if ((env.gitlabAfter ?: '') ==~ /0{40}/) {
                        currentBuild.description = '삭제 이벤트 — 배포 없음'
                        return
                    }

                    def ref      = env.gitlabBranch ?: ''
                    def shortSha = sh(returnStdout: true, script: 'git rev-parse --short=7 HEAD').trim()
                    def services = []

                    if (ref.startsWith('refs/tags/')) {
                        // v* 태그: 릴리스 확정 — 전체 서비스, 태그명 그대로
                        def tag = ref - 'refs/tags/'
                        if (!(tag ==~ /v\d+\.\d+\.\d+/)) {   // app-v* 등은 서버 배포 아님
                            currentBuild.description = "서버 배포 대상 아닌 태그: ${tag}"
                            return
                        }
                        env.DEPLOY_TAG = tag
                        services = ['web', 'backend', 'ai']
                        // 알림용 비교 기준: 직전 릴리스 태그 (새 태그가 걸린 HEAD는 제외)
                        env.DEPLOY_BASE = sh(returnStdout: true,
                            script: "git describe --tags --match 'v*' --abbrev=0 HEAD~1 2>/dev/null || true").trim()
                    } else if (ref.startsWith('release/app-')) {
                        // 모바일 릴리스 브랜치 — 서버 배포 대상 아님 (app-v* 태그와 동일 취급)
                        currentBuild.description = "모바일 릴리스 — 서버 배포 없음: ${ref}"
                        return
                    } else if (ref.startsWith('release/')) {
                        // release/* push: rc 태그 — 마지막 성공 배포 이후 변경된 서비스만
                        env.DEPLOY_TAG = "${ref - 'release/'}-rc.${env.BUILD_NUMBER}-${shortSha}"
                        def base    = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
                        def hasBase = base && sh(returnStatus: true,
                                                 script: "git cat-file -e ${base} 2>/dev/null") == 0
                        if (!hasBase) {
                            // 브랜치 첫 빌드 등 기준이 없으면 마지막 릴리스(v* 태그)와 비교
                            base = sh(returnStdout: true,
                                      script: "git describe --tags --match 'v*' --abbrev=0 2>/dev/null || true").trim()
                            hasBase = base as boolean
                        }
                        echo "변경 비교 기준: ${hasBase ? base : '(없음 — 전체 배포)'}"
                        env.DEPLOY_BASE = hasBase ? base : ''
                        if (hasBase) {
                            def changedFiles = sh(
                                returnStdout: true,
                                script: "git diff --name-only ${base} HEAD"
                            ).trim().readLines()

                            services = []

                            // 모바일 전용 파일을 제외한 front 변경은 웹 배포 대상으로 본다.
                            def webChanged = changedFiles.any { path ->
                                path.startsWith('front/') &&
                                !path.startsWith('front/apps/mobile/')
                            }

                            if (webChanged) {
                                services << 'web'
                            }

                            if (changedFiles.any { path ->
                                path.startsWith('apps/backend/')
                            }) {
                                services << 'backend'
                            }

                            if (changedFiles.any { path ->
                                path.startsWith('apps/ai/')
                            }) {
                                services << 'ai'
                            }
                        } else {
                            // 비교할 이전 커밋이 없으면 안전하게 서버 서비스 전체 배포
                            services = ['web', 'backend', 'ai']
                        }
                        if (!services) {
                            currentBuild.description = '서버 배포 대상 변경 없음'
                            return
                        }
                    } else {
                        currentBuild.description = "배포 트리거가 아닌 ref: ${ref}"
                        return
                    }

                    env.DEPLOY_SERVICES = services.join(' ')
                    currentBuild.description = "${env.DEPLOY_TAG} → [${env.DEPLOY_SERVICES}]"
                    echo "배포 계획: [${env.DEPLOY_SERVICES}] 태그 ${env.DEPLOY_TAG}"
                }
            }
        }

        // ── 2. 인프라 파일 동기화 — 원본을 서버 사본에 매 배포마다 반영 ──
        //    .env.config는 Jenkins Secret file(itda-env-config)이 원본이다.
        //    제외: .env.tags(활성 태그 상태 — deploy.sh가 갱신, 서버가 원본),
        //          nginx/inc(활성 슬롯 상태, 서버가 원본),
        //          deploy/jenkins(배포 중 자기 재생성 불가 — 수동 반영)
        stage('Sync') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'ec2-deploy-key',
                                                   keyFileVariable: 'SSH_KEY',
                                                   usernameVariable: 'SSH_USER'),
                                 file(credentialsId: 'itda-env-config', variable: 'ENV_CONFIG')]) {
                    sh """
                        scp -i "\$SSH_KEY" -o StrictHostKeyChecking=accept-new \\
                            deploy/docker-compose.prod.yml \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/home/ubuntu/app/
                        scp -i "\$SSH_KEY" "\$ENV_CONFIG" \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/home/ubuntu/app/.env.config
                        scp -i "\$SSH_KEY" scripts/deploy.sh scripts/cleanup-images.sh \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/home/ubuntu/app/scripts/
                        scp -i "\$SSH_KEY" deploy/nginx/itda.conf deploy/nginx/connection-upgrade.conf \\
                            "\$SSH_USER"@${DEPLOY_HOST}:/tmp/
                        ssh -i "\$SSH_KEY" "\$SSH_USER"@${DEPLOY_HOST} '
                            set -e
                            chmod +x /home/ubuntu/app/scripts/*.sh
                            chmod 600 /home/ubuntu/app/.env.config
                            sudo cp /etc/nginx/sites-available/itda /tmp/itda.rollback
                            sudo cp /tmp/itda.conf /etc/nginx/sites-available/itda
                            sudo cp /tmp/connection-upgrade.conf /etc/nginx/conf.d/connection-upgrade.conf
                            if sudo nginx -t; then
                                sudo systemctl reload nginx
                            else
                                echo "[sync] nginx 설정 오류 — 이전 설정으로 복원하고 배포를 중단한다"
                                sudo cp /tmp/itda.rollback /etc/nginx/sites-available/itda
                                exit 1
                            fi
                            rm -f /tmp/itda.conf /tmp/connection-upgrade.conf
                            sudo rm -f /tmp/itda.rollback
                        '
                    """
                }
            }
        }

        // ── 3. 이미지 빌드 (호스트 Docker 저장소에 남는다 = 롤백 지점) ──
        stage('Build') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                script {
                    env.DEPLOY_SERVICES.split(' ').each { svc ->
                        if (svc == 'web') {
                            sh "docker build --target web -t itda-web:${env.DEPLOY_TAG} front"
                        } else {
                            sh "docker build -t itda-${svc}:${env.DEPLOY_TAG} apps/${svc}"
                        }
                    }
                }
            }
        }

        // ── 4. 자기 호스트에 SSH로 접속해 deploy.sh 실행 (blue-green) ──
        stage('Deploy') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                withCredentials([sshUserPrivateKey(credentialsId: 'ec2-deploy-key',
                                                   keyFileVariable: 'SSH_KEY',
                                                   usernameVariable: 'SSH_USER')]) {
                    script {
                        env.DEPLOY_SERVICES.split(' ').each { svc ->
                            sh """
                                ssh -i "\$SSH_KEY" -o StrictHostKeyChecking=accept-new \\
                                    "\$SSH_USER"@${DEPLOY_HOST} \\
                                    "${DEPLOY_SCRIPT} ${svc} ${env.DEPLOY_TAG}"
                            """
                        }
                    }
                }
            }
        }

        // ── 5. 이미지 보존 정책 집행 (rc/v* 각 최근 3개 유지) ──────────
        //    실패해도 배포는 이미 끝났으므로 빌드를 실패시키지 않는다.
        stage('Cleanup') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                catchError(buildResult: 'SUCCESS', stageResult: 'FAILURE') {
                    withCredentials([sshUserPrivateKey(credentialsId: 'ec2-deploy-key',
                                                       keyFileVariable: 'SSH_KEY',
                                                       usernameVariable: 'SSH_USER')]) {
                        sh """
                            ssh -i "\$SSH_KEY" -o StrictHostKeyChecking=accept-new \\
                                "\$SSH_USER"@${DEPLOY_HOST} \\
                                /home/ubuntu/app/scripts/cleanup-images.sh
                        """
                    }
                }
            }
        }
    }

    // 참고: GitLab 커밋 상태 회신은 하지 않는다 — 보호 브랜치/태그에는 현재 토큰
    // 권한(Developer)으로 기록할 수 없고(403), 배포 이력의 원본은 Jenkins 빌드
    // 기록이다. 결과 통지는 아래 Mattermost 알림이 담당한다.
    post {
        success {
            script {
                if (env.DEPLOY_SERVICES) {
                    def body = "\n(변경 내역 비교 기준 없음 — 첫 배포)"
                    if (env.DEPLOY_BASE) {
                        // 1순위: 커밋 로그를 GMS로 한국어 요약 (팀원이 읽는 변경 요약)
                        def summary = ''
                        try {
                            withCredentials([string(credentialsId: 'gms-api-key', variable: 'GMS_API_KEY')]) {
                                summary = sh(returnStdout: true, script: """
                                    export CHANGES="\$(git log --no-merges --pretty=format:'%s%n%b' ${env.DEPLOY_BASE}..HEAD | head -c 8000)"
                                    docker run --rm -i -e CHANGES -e GMS_API_KEY -e GMS_URI -e GMS_MODEL \\
                                        python:3.12-slim python - < scripts/deploy-summary.py
                                """).trim()
                            }
                        } catch (e) {
                            echo "변경 요약 실패(무시): ${e}"
                        }
                        // 2순위(폴백): 커밋 제목 목록 — 알림은 항상 나간다
                        if (!summary) {
                            summary = sh(returnStdout: true,
                                script: "git log --no-merges --pretty=format:'• %s (%an)' ${env.DEPLOY_BASE}..HEAD | head -15").trim()
                        }
                        if (summary) {
                            body = "\n**변경 요약** (`${env.DEPLOY_BASE}` 이후):\n${summary}"
                        }
                    }
                    notifyMM("✅ **배포 완료** — `${env.DEPLOY_TAG}` → ${env.DEPLOY_SERVICES.replace(' ', ', ')}${body}\n[빌드 로그](${env.BUILD_URL}console)")
                }
            }
        }
        failure {
            script {
                if (env.DEPLOY_SERVICES) {
                    notifyMM("🔴 **배포 실패** — `${env.DEPLOY_TAG}` (${env.DEPLOY_SERVICES.replace(' ', ', ')})\n헬스체크 실패 시 자동 취소되므로 서비스는 직전 버전으로 유지 중입니다.\n[빌드 로그](${env.BUILD_URL}console)")
                }
            }
        }
    }
}
