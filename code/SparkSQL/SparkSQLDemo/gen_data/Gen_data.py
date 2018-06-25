# -*- coding: utf-8 -*-
import random
import os
import sys
import string
import shutil
from pandas import DataFrame
from multiprocessing import Process


def create_rank_set(whole_size, size, t_id):
    """
    :type begin_num: int
    :type size: int
    :type t_id: int
    :param size:
    """
    names = []
    rank1 = []
    rank2 = []
    access = []
    chars = string.ascii_lowercase
    chars += string.ascii_lowercase
    rate_size = size/10

    for lp in range(0, size):
        name_length = random.randint(20, 50)
        name = ''.join(random.sample(chars, name_length))
        names.append(name)
        rank1.append(random.randint(0, 100))
        rank2.append(random.randint(0, 200))
        access.append(random.randint(100, 10000000))
        if (lp+1) % rate_size == 0:
            print(str(t_id) + " : " + str((lp+1)/rate_size * 10) + "% created")

    print(str(t_id) + " : Writing to csv files...")
    rank_data = DataFrame(data={
        "name": names,
        "rank1": rank1,
        "rank2": rank2
    },
    )
    rank_data.to_csv("rank_%d/part_%d.csv" % (whole_size, t_id), index=False)
    access_data = DataFrame(data={
        "name": names,
        "access": access
    })
    access_data.to_csv("access_%d/part_%d.csv" % (whole_size, t_id), index=False)


def create_or_rebuild_dir(dir_path):
    """
    :type dir_path: str
    :param dir_path:
    """
    is_exist = os.path.exists(dir_path)
    if is_exist:
        shutil.rmtree(dir_path)
    os.makedirs(dir_path)


if __name__ == '__main__':
    try:
        data_size = int(sys.argv[1])
        p_num = int(sys.argv[2])
    except IndexError as e:
        print("Now using default data size 100000")
        data_size = 10000
        p_num = 4
    process_num = p_num
    print("Create %d rows of data" % data_size)
    print("using %d Process" % process_num)
    create_or_rebuild_dir("rank_%d" % data_size)
    create_or_rebuild_dir("access_%d" % data_size)
    partition_size = int(data_size/process_num)
    for i in range(process_num):
        p = Process(target=create_rank_set, args=(data_size, partition_size, i))
        p.start()
