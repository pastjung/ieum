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
pipeline {
    agent any

    options {
        gitLabConnection('ssafy-gitlab')
        disableConcurrentBuilds()            // 배포 중 배포 금지 (.env/inc 동시 수정 방지)
        timestamps()
        timeout(time: 40, unit: 'MINUTES')
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    environment {
        DEPLOY_HOST   = 'host.docker.internal'            // Jenkins 컨테이너에서 본 자기 호스트
        DEPLOY_SCRIPT = '/home/ubuntu/app/scripts/deploy.sh'
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
                    } else if (ref.startsWith('release/')) {
                        // release/* push: rc 태그 — 마지막 성공 배포 이후 변경된 서비스만
                        env.DEPLOY_TAG = "${ref - 'release/'}-rc.${env.BUILD_NUMBER}-${shortSha}"
                        def base    = env.GIT_PREVIOUS_SUCCESSFUL_COMMIT
                        def hasBase = base && sh(returnStatus: true,
                                                 script: "git cat-file -e ${base} 2>/dev/null") == 0
                        if (hasBase) {
                            services = ['web', 'backend', 'ai'].findAll { svc ->
                                sh(returnStatus: true,
                                   script: "git diff --name-only ${base} HEAD | grep -q '^apps/${svc}/'") == 0
                            }
                        } else {
                            services = ['web', 'backend', 'ai']   // 비교 기준 없음(첫 빌드 등) → 전체
                        }
                        if (!services) {
                            currentBuild.description = 'apps/* 변경 없음 — 배포할 서비스 없음'
                            return
                        }
                    } else {
                        currentBuild.description = "배포 트리거가 아닌 ref: ${ref}"
                        return
                    }

                    env.DEPLOY_SERVICES = services.join(' ')
                    currentBuild.description = "${env.DEPLOY_TAG} → [${env.DEPLOY_SERVICES}]"
                    updateGitlabCommitStatus name: 'deploy', state: 'running'
                    echo "배포 계획: [${env.DEPLOY_SERVICES}] 태그 ${env.DEPLOY_TAG}"
                }
            }
        }

        // ── 2. 이미지 빌드 (호스트 Docker 저장소에 남는다 = 롤백 지점) ──
        stage('Build') {
            when { expression { env.DEPLOY_SERVICES } }
            steps {
                script {
                    env.DEPLOY_SERVICES.split(' ').each { svc ->
                        sh "docker build -t itda-${svc}:${env.DEPLOY_TAG} apps/${svc}"
                    }
                }
            }
        }

        // ── 3. 자기 호스트에 SSH로 접속해 deploy.sh 실행 (blue-green) ──
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
    }

    post {
        success {
            script { if (env.DEPLOY_SERVICES) updateGitlabCommitStatus name: 'deploy', state: 'success' }
        }
        failure {
            script { if (env.DEPLOY_SERVICES) updateGitlabCommitStatus name: 'deploy', state: 'failed' }
        }
        aborted {
            script { if (env.DEPLOY_SERVICES) updateGitlabCommitStatus name: 'deploy', state: 'canceled' }
        }
    }
}
