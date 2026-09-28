.PHONY: up down run test load toxic-latency toxic-timeout toxic-reset psql threaddump

up:            ## start postgres, wiremock, toxiproxy, prometheus, grafana
	docker compose up -d --wait postgres wiremock toxiproxy prometheus grafana

down:          ## stop everything and delete volumes
	docker compose down -v

run:           ## run the app on the host with lab JVM flags (see build.gradle.kts)
	mkdir -p dumps && ./gradlew bootRun

test:
	./gradlew test

load:          ## make load S=01 V=broken
	docker compose run --rm k6 run -e VARIANT=$(V) /scripts/$(notdir $(wildcard k6/$(S)-*.js))

toxic-latency: ## make toxic-latency TARGET=downstream MS=30000
	./scripts/toxics.sh latency $(TARGET) $(MS)

toxic-timeout: ## make toxic-timeout TARGET=postgres
	./scripts/toxics.sh timeout $(TARGET)

toxic-reset:   ## remove all toxics
	./scripts/toxics.sh clear

psql:          ## psql straight into postgres (bypasses toxiproxy)
	docker compose exec postgres psql -U lab failurelab

threaddump:    ## write a thread dump of the running app to dumps/
	mkdir -p dumps && jcmd $$(jps | awk '/FailureLabApplication/{print $$1}') Thread.print > dumps/threads-$$(date +%s).txt && ls -t dumps | head -1
