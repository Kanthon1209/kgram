# Build on a machine with enough RAM. Do not build this image on the 2Gi demo ECS.
FROM golang:1.22-bookworm AS build
WORKDIR /src
COPY services/gateway/go.mod services/gateway/go.sum ./
RUN go mod download
COPY services/gateway/ ./
RUN CGO_ENABLED=0 go build -o /kgram-gateway .

FROM gcr.io/distroless/static-debian12:nonroot
COPY --from=build /kgram-gateway /kgram-gateway
USER nonroot
EXPOSE 8080 8443
ENTRYPOINT ["/kgram-gateway"]
