---
layout: global
title: Using SystemML with Native BLAS support
description: Using SystemML with Native BLAS support
---
<!--
{% comment %}
Licensed to the Apache Software Foundation (ASF) under one or more
contributor license agreements.  See the NOTICE file distributed with
this work for additional information regarding copyright ownership.
The ASF licenses this file to you under the Apache License, Version 2.0
(the "License"); you may not use this file except in compliance with
the License.  You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
{% endcomment %}
-->

* This will become a table of contents (this text will be scraped).
{:toc}

<br/>

# User Guide

By default, SystemML implements all its matrix operations in Java.
This simplifies deployment especially in a distributed environment.

In some cases (such as deep learning), the user might want to use native BLAS
rather than SystemML's internal Java library for performing single-node
operations such matrix multiplication, convolution, etc.

To allow SystemML to use native BLAS rather than internal Java library,
please set the configuration property `native.blas` to `auto`.
Other possible options are: `mkl`, `openblas` and `none`.
The first two options will only attempt to use the respective BLAS libraries.

By default, SystemML will first attempt to use Intel MKL (if installed)
and then OpenBLAS (if installed).
If both Intel MKL and OpenBLAS are not available, SystemML
falls back to its internal Java library.

The current version of SystemML only supports BLAS on **Linux** machines.

## Step 1: Install BLAS

### Option 1: Install Intel MKL (recommended)

Download and install the [community version of Intel MKL](https://software.intel.com/sites/campaigns/nest/).
Intel requires you to first register your email address and then sends the download link to your email address
with license key.

* Linux users will have to extract the downloaded `.tgz` file, execute `install.sh` and follow the guided setup.

### Option 2: Install OpenBLAS  

```bash
# The default OpenBLAS (via yum/apt-get) uses its internal threading rather than OpenMP, 
# which can lead to performance degradation when using SystemML. So, instead we recommend that you
# compile OpenBLAS from the source. 
# RedHat / CentOS: sudo yum install openblas
# Ubuntu: sudo apt-get install openblas
git clone https://github.com/xianyi/OpenBLAS.git
cd OpenBLAS/
make clean
make USE_OPENMP=1
sudo make install
# After installation, you may also want to add `/opt/OpenBLAS/lib` to your LD_LIBRARY_PATH or `java.library.path`.
```

You can check if the OpenBLAS on you system is compiled with OpenMP or not using following commands:

```bash
$ ldconfig -p | grep libopenblas.so
libopenblas.so (libc6,x86-64) => /opt/OpenBLAS/lib/libopenblas.so
$ ldd /opt/OpenBLAS/lib/libopenblas.so | grep libgomp
libgomp.so.1 => /lib64/libgomp.so.1
```

If you don't see any output after the second command, then OpenBLAS installed on your system is using its internal threading.
In this case, we highly recommend that you reinstall OpenBLAS using the above commands.


## Step 2: Install other dependencies

```bash
# Centos/RedHat
sudo yum install gcc-c++
# Ubuntu
sudo apt-get install g++ 
```

We also depend on GNU OpenMP (gomp) which will be installed by GCC.
To find the location of `gomp` on your system, please use the command `ldconfig -p | grep libgomp`.
If gomp is available as `/lib64/libgomp.so.1` instead of `/lib64/libgomp.so`,
please add a softlink to it:

```bash
sudo ln -s /lib64/libgomp.so.1 /lib64/libgomp.so
```
	
## Step 3: Provide the location of the native libraries

1. Pass the location of the native libraries using command-line options:

- [Spark](http://spark.apache.org/docs/latest/configuration.html): `--conf spark.executorEnv.LD_LIBRARY_PATH=/path/to/blas-n-other-dependencies`
- Java: `-Djava.library.path=/path/to/blas-n-other-dependencies`

2. Alternatively, you can add the location of the native libraries (i.e. BLAS and other dependencies) 
to the environment variable `LD_LIBRARY_PATH` (on Linux). 
If you want to use SystemML with Spark, please add the following line to `spark-env.sh` 
(or to the bash profile).

	```bash
	export LD_LIBRARY_PATH=/path/to/blas-n-other-dependencies
	```


## Common issues on Linux

1. Unable to load `gomp`

First make sure if gomp is available on your system.

	```bash
	ldconfig -p | grep  libgomp
	```

If the above command returns no results, then you may have to install `gcc`.
On the other hand, if the above command only returns libgomp with major suffix (such as `so.1`),
then please execute the below command:

	```bash
	sudo ln -s /lib64/libgomp.so.1 /usr/lib64/libgomp.so
	```

2. Unable to load `mkl_rt`

By default, Intel MKL libraries will be installed in the location `/opt/intel/mkl/lib/intel64/`.
Make sure that this path is accessible to Java as per instructions provided in the above section.

3. Unable to load `openblas`

By default, OpenBLAS libraries will be installed in the location `/opt/OpenBLAS/lib/`.
Make sure that this path is accessible to Java as per instructions provided in the above section.

# Developer Guide

This section describes how to compile shared libraries in the folder `src/main/cpp/lib`. 
This is required when the developer makes changes to cpp directory or while validating the source package during the release process.

## Intro to CMake
If you are familiar with cmake, skip this section.

In a regular project with a Makefile, the compiled object files are placed in the same directory as the source.
Sometimes we don't want to pollute the source tree. We might also want to have different binaries for different configurations. For instance, if we want to link a binary with separate libraries.
CMake supports out of source tree builds. As an illustration, you can create a directory called "BUILD" and invoke cmake like so : `cmake <path/to/source>`. The makefile and other config files are placed in this "BUILD" directory. You can now say `make` and the compiled objects and binary files are created in this directory. You can then create another "BUILD2" directory and repeat the process.
You can pass options to cmake as well. In this instance, it might be to specify whether to build with Intel MKL or OpenBLAS. This can be done from the command line with a "-D" appended to it, but more interestingly, it can also be done form a n-curses GUI which is invoked as `ccmake <path/to/source>`. (You may need to install this separately).
Also, the C, C++ compilers and their flags are picked up by cmake when set in standard environment variables. These are respectively `CC`, `CXX`, `CFLAGS` & `CXFLAGS`. As an example, they may be specified as:

	CXX=gcc-6 cmake ..

For this project, I typically make a directory in the `cpp` folder (this folder) and name it the config I use. For instance, `INTEL` for Intel MKL and `OPENBLAS` for OpenBLAS.

1. Install `g++`, OpenBLAS and MKL using the above instructions

2. Set `JAVA_HOME` to JDK.

	export JAVA_HOME=<path to JDK 1.8>

3. Install cmake

	```bash
	# Centos/RedHat
	sudo yum install cmake3
	# Ubuntu
	sudo apt-get install cmake
	```

4. Compile the libs using the below script. 

	```bash
	mkdir INTEL && cd INTEL
	cmake -DUSE_INTEL_MKL=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++ -DCMAKE_CXX_FLAGS="-DUSE_GNU_THREADING -m64" ..
	make install
	cd ..
	mkdir OPENBLAS && cd OPENBLAS
	cmake -DUSE_OPEN_BLAS=ON -DCMAKE_BUILD_TYPE=Release -DCMAKE_C_COMPILER=gcc -DCMAKE_CXX_COMPILER=g++ -DCMAKE_CXX_FLAGS="-m64" ..
	make install
	cd ..
	# The below script helps maintain this document as well as avoid accidental inclusion of non-standard dependencies.
	./check-dependency-linux-x86_64.sh
	```


The generated library files are placed in src/main/cpp/lib. This location can be changed from the CMakeLists.txt file.

The above script also validates whether additional dependencies have been added while compiling and warns the developer.  
The current set of dependencies other than MKL and OpenBLAS, are as follows:

- GNU Standard C++ Library: `libstdc++.so.6`
- GCC version 4.8 shared support library: `libgcc_s.so.1`
- The GNU libc libraries: `libm.so.6, libdl.so.2, libc.so.6, libpthread.so.0`
- GCC OpenMP v3.0 shared support library: `libgomp.so.1`
- Additional OpenBLAS dependencies: Fortran runtime (`libgfortran.so.3`) and GCC `__float128` shared support library (`libquadmath.so.0`)

If CMake cannot detect your OpenBLAS installation, set the `OpenBLAS_HOME` environment variable to the OpenBLAS Home.