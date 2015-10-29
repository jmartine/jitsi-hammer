#!/bin/bash

i="0"

while [ "$i" -lt "$2" ]
do
  echo "$3_$i:$1" >> /credentials
  ((i+=1))
done

exit 0
