# Vehicle positions
This service provides a GraphQL-api for fetching/streaming live vehicle-positions.

Built-in GraphQL-IDE: https://api.entur.io/realtime/v1/vehicles/graphiql

## Query
Enables fetching vehicle-positions in a GraphQL-api.

**Base URL:** https://api.entur.io/realtime/v1/vehicles/graphql

Example:
```
{
  vehicles(codespaceId:"SKY") {
    lastUpdated
    location {
      latitude
      longitude
    }
  }
}
```

Examples:
- [List of available codespaces](https://api.entur.io/realtime/v1/vehicles/graphiql?query=%7B%0A%20%20codespaces%20%7B%0A%20%20%20%20codespaceId%0A%20%20%7D%0A%7D%0A&variables=%7B%0A%20%20%22date%22%3A%20%222021-04-16%22%0A%7D)
- [List of available lines](https://api.entur.io/realtime/v1/vehicles/graphiql?query=%7B%0A%20%20lines%28codespaceId%3A%22SKY%22%29%20%7B%0A%20%20%20%20lineRef%0A%20%20%20%20lineName%0A%20%20%7D%0A%7D%0A&variables=%7B%0A%20%20%22date%22%3A%20%222021-04-16%22%0A%7D)
- [List of serviceJourneys for a given line](https://api.entur.io/realtime/v1/vehicles/graphiql?query=%7B%0A%20%20serviceJourneys%28lineRef%3A%22SKY%3ALine%3A10%22%29%7B%0A%20%20%20%20serviceJourneyId%0A%20%20%7D%0A%7D&variables=%7B%0A%20%20%22date%22%3A%20%222021-04-16%22%0A%7D)
- [All vehicles for codespace=SKY](https://api.entur.io/realtime/v1/vehicles/graphiql?query=%7B%0A%20%20vehicles(codespaceId%3A%22SKY%22)%20%7B%0A%20%20%20%20lastUpdated%0A%20%20%20%20location%20%7B%0A%20%20%20%20%20%20latitude%0A%20%20%20%20%20%20longitude%0A%20%20%20%20%7D%0A%20%20%7D%0A%7D&variables=%7B%0A%20%20%22date%22%3A%20%222021-04-16%22%0A%7D)

## Subscription
Enables creating a GraphQL-subscription that will open a websocket and let the server stream all updates to the client continuously. 

**Base URL:** wss://api.entur.io/realtime/v1/vehicles/subscriptions

Example:
```
subscription {
  vehicles(codespaceId:"SKY") {
    lastUpdated
    location {
      latitude
      longitude
    }
  }
}
```
Examples:
- [Stream all updates for codespace=SKY](https://api.entur.io/realtime/v1/vehicles/graphiql?query=subscription%20%7B%0A%20%20vehicles(codespaceId%3A%22SKY%22)%20%7B%0A%20%20%20%20lastUpdated%0A%20%20%20%20location%20%7B%0A%20%20%20%20%20%20latitude%0A%20%20%20%20%20%20longitude%0A%20%20%20%20%7D%0A%20%20%7D%0A%7D&variables=%7B%0A%20%20%22date%22%3A%20%222021-04-16%22%0A%7D)

More details about GraphQL-subscriptions: https://graphql.org/blog/subscriptions-in-graphql-and-relay/

## Situations
Service messages (SIRI-SX) describing disruptions and deviations. Available both as a query and as a
subscription, using the same filter arguments as the examples below.

Situations are filtered by the objects they affect — `lineRef`, `stopRef` (matching both affected stop
points and stop places), `serviceJourneyId`, `datedServiceJourneyId`, `operatorRef` and `mode` — as well
as by `codespaceId`, `severity`, `reportType` and `situationNumbers`.

`includeClosed` defaults differently for the query and the subscription, deliberately:

- `situations` (query) defaults to `includeClosed: false` — a one-time snapshot has no use for
  situations that are already gone.
- `situations` (subscription) defaults to `includeClosed: true` — a live subscriber needs to observe
  a situation transitioning to `progress: closed` so it can remove it from display. A situation that
  is closed is published to active subscribers once, with `progress: closed`, before it is removed.
  One consequence of this default: a new subscription's initial snapshot also includes any situations
  closed within the grace period, not just live ones. Pass `includeClosed: false` explicitly on the
  subscription to opt out of that.

The situation stream is only eventually consistent: two concurrent updates to the same situation can
reach subscribers in reverse version order. Clients should track the highest `version` seen per
`situationNumber` and discard any update that regresses it.

A situation published without a validity end time never expires and is retained indefinitely. `openEnded`
and `minAge` exist to find such situations:

```
{
  situations(codespaceId: "RUT") {
    situationNumber
    progress
    severity
    openEnded
    age
    summary { value language }
    affects {
      lines { lineRef lineName }
      stopPoints { id name }
    }
  }
}
```

Open-ended situations that have been active for more than 30 days:

```
{
  situations(openEnded: true, minAge: "P30D") {
    situationNumber
    codespace { codespaceId }
    age
  }
}
```