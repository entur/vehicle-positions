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