def label = "mongo-${UUID.randomUUID().toString()}"

podTemplate(label: label, yaml: """
apiVersion: v1
kind: Pod
metadata:
  labels:
    some-label: some-label-value
spec:
  containers:
  - name: maven
    image: maven:3.3.9-jdk-8-alpine
    command: ['cat']
    tty: true
  - name: mongo
    image: mongo
"""
  ) {

    node(label) {
        stage('Integration Test') {
            try {
                container('maven') {
                    sh 'nc -z localhost:27017 && echo "connected to mongo db"'
                    // sh 'mvn -B clean failsafe:integration-test' // real integration test

                    def mongoLog = containerLog(name: 'mongo', returnLog: true, tailingLines: 5, sinceSeconds: 20, limitBytes: 50000)
                    assert mongoLog.contains('connection accepted from 127.0.0.1:')
                    sh 'echo failing build; false'
                }
            } catch (Exception e) {
                containerLog 'mongo'
                throw e
            }
        }
    }
}
