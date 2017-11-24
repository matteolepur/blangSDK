#!/bin/bash

# ACE
rm -rf ace-master
git clone https://github.com/ajaxorg/ace.git
mv ace ace-master
cd ace-master
git reset --hard c3403f1fbdf22cfff2cb1dda584b8e04467cd372
cd -


# Bootstrap
rm -rf bootstrap
rm -rf www/dist
git clone https://github.com/twbs/bootstrap.git
cd bootstrap
git reset --hard v3.3.7
cp -r dist ../www/
cd -