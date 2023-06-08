Containers logSince API
=======================

# Description

This API is intended to feed container log data to the frontend UI for presentation to the user.
It can work in a few different ways depending on the state of the container.

# Intended Usage

Make a request to the API. Display the string in the `content` key.

If the `timestamp` value is `-1`, there will be no more content and you can stop making requests.

If the `timestamp` value is some other integer, make another request with that value as the `since` parameter.
If your new request gets any more `content`, it will be from something newly logged since last time you made a
request. Append this `content` to what you already have. Repeat.

# Modes of Operation
## Complete Container
If the container is finished, we expect that the logs have been saved to a local file during container finalization.
We return the entire contents of the file, and signal that there is no more to read by setting the timestamp to `-1`.

## Live Container
If the container is not finished—i.e. "live"—the API requests the logs from the backend API. But we do not fetch
all the logs, only the logs that have been generated since the last request. This is accomplished by a `since` parameter
on the API through which the user can send us a timestamp in epoch seconds.

If the `since` parameter is not present, we fetch all the logs that have been generated from the beginning of the container's
life up to "now". That "now" value is returned to the user in the response as the `timestamp` with the expectation that
they will make another request after some time and use the value we gave them as the new `since` parameter.
In that way the next request will only return any incremental updates to the logs.

### Timestamp
The `timestamp` is determined according to the following rules:

* If we got any logs from the backend API, they will contain a timestamp on each line. We request those timestamps
deliberately so we can do this calculation, but we strip them out of the logs before returning them to the user.
We parse out the timestamp from the last line of the logs, convert it to an epoch second, and add one before returning
it to the user.
* If we didn't get any logs or couldn't parse a timestamp from them, we return back the `since` value.
* If there was no `since` value, we use the time the user made the request.

# Params

Make a `GET` request to `/xapi/containers/{containerId}/logSince/{stdout or stderr}`. If you have a timestamp in epoch
seconds, include it as a `since` query parameter.

# Return value

The API returns an object serialized to JSON with the following keys:

* `content` **`string`** — The contents of the log, as a string. Could be the whole log or could be a fragment to be appended to the rest.
* `fromFile` **`boolean`** — `true` if the log contents were read from a log file which had been saved during container finalization, or
  `false` if the contents came from requesting the logs from the backend API for a live container.
* `timestamp` **`integer`** — If the container is complete, this will always be `-1`. If the container is still live, this is a timestamp
  in epoch seconds. See description above.
* `bytesRead` **`integer`** — This is deprecated. Its value is always `-1`.
