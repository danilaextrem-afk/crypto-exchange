FROM golang:1.24-alpine AS build
WORKDIR /src
COPY go.mod ./
COPY *.go ./
RUN CGO_ENABLED=0 go build -trimpath -ldflags="-s -w" -o /freevpn-node .

FROM alpine:3.20
RUN apk add --no-cache wireguard-tools iptables iproute2 ca-certificates
COPY --from=build /freevpn-node /usr/local/bin/freevpn-node
COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
