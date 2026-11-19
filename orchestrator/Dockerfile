FROM golang:1.24 AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /orchestrator ./cmd/orchestrator

FROM gcr.io/distroless/static-debian12
COPY --from=build /orchestrator /orchestrator
USER nonroot
EXPOSE 8090
ENTRYPOINT ["/orchestrator"]
